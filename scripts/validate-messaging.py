#!/usr/bin/env python3
"""Stage 3 acceptance. Real Go/Java/PostgreSQL inside a disposable IsolatedStack with synthetic signed
webhooks and the fake provider. Stops isolated services for outage checks; never calls the real
provider and never touches the developer stack.
"""
import concurrent.futures
import time
import urllib.parse
import urllib.request
from validation_support import IsolatedStack, poll, request


def main():
    with IsolatedStack("messaging", services=("catalog-service", "demand-service", "messaging-service")) as stack:
        session = stack.login()
        token, merchant = session["accessToken"], session["merchantId"]
        messaging, demand, catalog = stack.url("messaging-service"), stack.url("demand-service"), stack.url("catalog-service")
        provider = stack.provider
        suffix = stack.run_id
        phone = stack.phones.next()

        def sql(statement):
            return stack.sql("messaging", statement)

        def inbound(text, identifier=None):
            return stack.inbound(phone, text, identifier)

        def event_state(identifier):
            return sql(f"SELECT status FROM webhook_events WHERE merchant_id='{merchant}' AND provider_event_id='inbound:{identifier}';")

        # Build a standalone generated Go client and run it on the private Compose network.
        binary = stack.go_build("demand-grpc-check")
        check_env = {"MESSAGING_DEMAND_TOKEN": stack.values["MESSAGING_DEMAND_TOKEN"], "CHECK_MERCHANT_ID": merchant}

        def grpc_check(deadline=False):
            print(stack.go_run(binary, env={**check_env, "CHECK_DEADLINE": str(deadline).lower()}))

        grpc_check()

        # A stopped dependency may fail immediately. Hold a bounded read lock instead
        # to prove the Java call is in flight when the Go deadline expires.
        lock = stack.lock_process("demand")
        lock.stdin.write("BEGIN; SET LOCAL lock_timeout='3s'; LOCK TABLE merchants IN ACCESS EXCLUSIVE MODE; SELECT 'locked'; SELECT pg_sleep(3); ROLLBACK;\n")
        lock.stdin.close()
        try:
            assert lock.stdout.readline().strip() == "locked", "deadline fixture did not acquire its bounded lock"
            grpc_check(True)
        finally:
            lock.wait(timeout=10)
            lock.stdout.close()
            lock.stderr.close()

        url = messaging + "/webhooks/whatsapp?" + urllib.parse.urlencode({"hub.mode": "subscribe", "hub.verify_token": stack.verify_token, "hub.challenge": "123456"})
        with urllib.request.urlopen(url, timeout=5) as response:
            assert response.status == 200 and response.read() == b"123456"

        identifier, payload = inbound("HELP")
        before = sql("SELECT count(*) FROM webhook_events;")
        assert stack.signed(payload, False)[0] == 401
        assert sql("SELECT count(*) FROM webhook_events;") == before

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: stack.signed(payload), range(8)))

        assert all(code == 200 for code, _ in results)
        assert event_state(identifier) == "PROCESSED"

        captured = stack.provider_requests()
        assert len(captured) == 1 and "STOCK <SKU>" in captured[0]["body"]["text"]["body"]
        outbound = captured[0]["id"]

        assert sql(f"SELECT count(*) FROM message_records WHERE merchant_id='{merchant}' AND provider_message_id='{identifier}' AND direction='INBOUND';") == "1"
        assert sql(f"SELECT count(*) FROM message_records WHERE merchant_id='{merchant}' AND provider_message_id='{outbound}' AND direction='OUTBOUND';") == "1"

        correlation = captured[0]["body"]["biz_opaque_callback_data"]
        assert sql(f"SELECT count(*) FROM message_records WHERE correlation_id='{correlation}';") == "2"
        assert stack.logs("demand-service").count("Customer command processed requestId=" + correlation) == 1

        print("PASS verification, invalid signature isolation, signed HELP, persistence, correlation, eight concurrent duplicates: one Demand call and one outbound")

        now = int(time.time())
        for state, at in [("read", now), ("delivered", now - 1), ("sent", now - 2), ("failed", now - 3), ("read", now)]:
            assert stack.signed(stack.envelope({"statuses": [{"id": outbound, "status": state, "timestamp": str(at)}]}))[0] == 200

        assert sql(f"SELECT count(*) FROM delivery_events WHERE merchant_id='{merchant}' AND provider_message_id='{outbound}';") == "4"
        assert sql(f"SELECT status FROM message_records WHERE merchant_id='{merchant}' AND provider_message_id='{outbound}' AND direction='OUTBOUND';") == "READ"
        assert stack.signed(stack.envelope({"statuses": [{"id": "synthetic.unknown." + suffix, "status": "sent", "timestamp": str(now)}]}))[0] == 200

        print("PASS sent/delivered/read/failed status history, duplicate callback, unknown ID and chronological state")

        product = request(catalog, "POST", "/api/products", {"name": "Stage 3 test phone", "brand": "Demo", "category": "Phones"}, token, 201)
        sku = "STAGE3-" + suffix
        variant = request(catalog, "POST", f"/api/products/{product['id']}/variants", {"sku": sku, "attributes": {"color": "Black"}}, token, 201)

        _, payload = inbound("STOCK " + sku)
        assert stack.signed(payload)[0] == 200
        assert stack.provider_requests()[-1]["body"]["text"]["body"].endswith("currently out of stock.")

        waitlist = request(demand, "POST", "/api/waitlists", {"customerPhone": phone, "variantId": variant["id"], "consent": True}, token, expected=201)

        _, payload = inbound("STOP")
        assert stack.signed(payload)[0] == 200
        assert request(demand, "GET", f"/api/waitlists/{waitlist['id']}", token=token)["status"] == "OPTED_OUT"
        request(demand, "POST", f"/api/customers/{waitlist['customerId']}/opt-in", token=token)

        renewed = request(demand, "POST", "/api/waitlists", {"customerPhone": phone, "variantId": variant["id"], "consent": False}, token, 201)
        identifier, delayed = inbound("STOP")
        delayed["entry"][0]["changes"][0]["value"]["messages"][0]["timestamp"] = str(int(time.time()) - 25 * 3600)
        sends_before_delayed = len(stack.provider_requests())

        assert stack.signed(delayed)[0] == 200 and event_state(identifier) == "EXPIRED"
        assert request(demand, "GET", f"/api/waitlists/{renewed['id']}", token=token)["status"] == "OPTED_OUT"
        assert len(stack.provider_requests()) == sends_before_delayed

        request(catalog, "PATCH", f"/api/variants/{variant['id']}/inventory", {"quantity": 3}, token)
        _, payload = inbound("STOCK " + sku)
        assert stack.signed(payload)[0] == 200
        assert stack.provider_requests()[-1]["body"]["text"]["body"].endswith("currently available.")

        print("PASS exact SKU through production Catalog gRPC, authoritative zero/positive stock, STOP suppresses existing waitlist; delayed STOP updates consent without an expired-window reply")

        stack.stop("catalog-service")
        _, payload = inbound("STOCK " + sku)
        assert stack.signed(payload)[0] == 200
        assert "cannot verify stock" in stack.provider_requests()[-1]["body"]["text"]["body"]
        stack.start("catalog-service")
        print("PASS Catalog outage: no guessed stock; safe fallback")

        stack.stop("demand-service")
        before_sends = len(stack.provider_requests())
        identifier, payload = inbound("HELP")
        start = time.monotonic()
        assert stack.signed(payload)[0] == 200 and time.monotonic() - start < 8
        assert event_state(identifier) == "DEMAND_FAILED"
        assert len(stack.provider_requests()) == before_sends
        stack.start("demand-service")

        # Reconnect after outage; the failed ID remains claimed, with no automatic replay.
        assert stack.signed(payload)[0] == 200
        assert len(stack.provider_requests()) == before_sends
        print("PASS Demand outage: bounded failure persisted, no fabricated reply, no duplicate replay")

        # Wait until the existing Messaging -> Demand channel has reconnected after the outage (gRPC backoff
        # varies), proven by a synthetic HELP that is PROCESSED end to end, instead of a fixed sleep.
        def demand_reachable():
            probe, probe_payload = inbound("HELP")
            return stack.signed(probe_payload)[0] == 200 and event_state(probe) == "PROCESSED"
        poll("Messaging -> Demand channel reconnected", demand_reachable, timeout=60, interval=1)
        before_sends = len(stack.provider_requests())
        stack.provider_mode("failure")
        identifier, payload = inbound("HELP")
        assert stack.signed(payload)[0] == 200
        assert event_state(identifier) == "PROVIDER_FAILED"

        assert sql(f"SELECT count(*) FROM message_records m JOIN webhook_events w ON w.id=m.webhook_event_id WHERE w.provider_event_id='inbound:{identifier}' AND m.direction='OUTBOUND' AND m.status='UNKNOWN' AND m.provider_message_id IS NULL;") == "1"
        assert stack.signed(payload)[0] == 200
        assert len(stack.provider_requests()) == before_sends + 1
        stack.provider_mode("success")
        print("PASS provider failure: uncertain state, no fabricated provider ID, one attempt")

    print("PASS local Stage 3 acceptance; real Meta acceptance remains separate; developer stack untouched")


if __name__ == "__main__":
    main()
