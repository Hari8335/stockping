#!/usr/bin/env python3
"""Stage 3 local acceptance. Real Go/Java/PostgreSQL, synthetic signed webhooks and fake Meta.
Temporarily overrides only Messaging configuration and stops services for outage checks.
Restores the base Compose configuration in finally; never calls the real provider.
"""
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from validation_support import ROOT, compose, endpoint, login, request, services


def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=True,
                          timeout=kwargs.pop("timeout", 120), **kwargs)


def main():
    config = services()
    session = login(config)
    token, merchant = session["accessToken"], session["merchantId"]
    messaging = endpoint(config, "messaging-service")
    demand = endpoint(config, "demand-service")
    catalog = endpoint(config, "catalog-service")
    container = compose("ps", "-q", "messaging-service").stdout.strip()
    network = next(iter(json.loads(docker("inspect", container).stdout)[0]["NetworkSettings"]["Networks"]))
    suffix = uuid.uuid4().hex[:12]
    provider_name = "stockping-test-provider-" + suffix
    phone = "+9477" + str(uuid.uuid4().int % 10_000_000).zfill(7)
    app_secret = "synthetic-app-secret"
    base_file = str(ROOT / "docker-compose.yml")

    def sql(statement):
        return compose("exec", "-T", "postgres", "sh", "-c",
                       'PGPASSWORD="$MESSAGING_DB_PASSWORD" psql -h 127.0.0.1 -U messaging_user -d messaging_db -v ON_ERROR_STOP=1 -At',
                       input=statement).stdout.strip()

    def signed(payload, valid=True):
        data = json.dumps(payload).encode()
        signature = hmac.new(app_secret.encode(), data, hashlib.sha256).hexdigest() if valid else "0" * 64
        req = urllib.request.Request(messaging + "/webhooks/whatsapp", data=data, method="POST",
                                     headers={"Content-Type": "application/json", "X-Hub-Signature-256": "sha256=" + signature})
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers.get("X-Request-ID")

    def envelope(value):
        return {"object": "whatsapp_business_account", "entry": [{"id": "100", "changes": [
            {"field": "messages", "value": {"messaging_product": "whatsapp", "metadata": {"phone_number_id": "200"}, **value}}]}]}

    def inbound(text, identifier=None):
        identifier = identifier or "synthetic.in." + uuid.uuid4().hex
        return identifier, envelope({"messages": [{"id": identifier, "from": phone[1:], "timestamp": str(int(time.time())),
                                                    "type": "text", "text": {"body": text}}]})

    def event_state(identifier):
        return sql(f"SELECT status FROM webhook_events WHERE merchant_id='{merchant}' AND provider_event_id='inbound:{identifier}';")

    with tempfile.TemporaryDirectory(prefix="stockping-stage3-") as tmp:
        override = Path(tmp) / "compose.json"
        override.write_text(json.dumps({"services": {"messaging-service": {"environment": {
            "WHATSAPP_ENABLED": "true", "WHATSAPP_VERIFY_TOKEN": "synthetic-verify-token",
            "WHATSAPP_APP_SECRET": app_secret, "WHATSAPP_ACCESS_TOKEN": "synthetic-access-token",
            "WHATSAPP_PHONE_NUMBER_ID": "200", "WHATSAPP_WABA_ID": "100", "WHATSAPP_GRAPH_API_VERSION": "v26.0",
            "WHATSAPP_MERCHANT_ID": merchant, "WHATSAPP_API_BASE_URL": f"http://{provider_name}:8080",
            "WHATSAPP_ALLOW_TEST_ENDPOINT": "true"}}}}))
        override.chmod(0o600)
        stopped_demand = stopped_catalog = False
        try:
            docker("run", "-d", "--name", provider_name, "--network", network, "-p", "127.0.0.1::8080",
                   "-v", f"{ROOT / 'scripts/fake-whatsapp.py'}:/server.py:ro", "python:3.13-alpine", "python", "/server.py")
            hostport = json.loads(docker("inspect", provider_name).stdout)[0]["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
            provider = "http://127.0.0.1:" + hostport
            compose("-f", base_file, "-f", str(override), "up", "-d", "--no-deps", "--wait", "--wait-timeout", "60", "messaging-service", timeout=90)
            # Build a standalone generated Go client and run it on the private Compose network.
            docker("run", "--rm", "-v", f"{ROOT}:/workspace", "-v", f"{tmp}:/checks", "-w", "/workspace/messaging-service",
                   "-e", "CGO_ENABLED=0", "stockping-proto-tools", "go", "build", "-o", "/checks/demand-grpc-check", "/workspace/scripts/demand-grpc-check.go", timeout=300)
            check_env = {**os.environ, "MESSAGING_DEMAND_TOKEN": config["demand-service"]["environment"]["MESSAGING_DEMAND_TOKEN"], "CHECK_MERCHANT_ID": merchant}

            def grpc_check(deadline=False):
                result = docker("run", "--rm", "--network", network, "-v", f"{tmp}:/checks:ro",
                                "-e", "MESSAGING_DEMAND_TOKEN", "-e", "CHECK_MERCHANT_ID", "-e", "CHECK_DEADLINE",
                                "alpine:3.23", "/checks/demand-grpc-check", env={**check_env, "CHECK_DEADLINE": str(deadline).lower()})
                print(result.stdout.strip())

            grpc_check()
            # A stopped dependency may fail immediately. Hold a bounded read lock instead
            # to prove the Java call is in flight when the Go deadline expires.
            lock = subprocess.Popen(["docker", "compose", "--project-directory", str(ROOT),
                                     "exec", "-T", "postgres", "sh", "-c",
                                     'PGPASSWORD="$DEMAND_DB_PASSWORD" psql -h 127.0.0.1 -U demand_user -d demand_db -v ON_ERROR_STOP=1 -qAt'],
                                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            lock.stdin.write("BEGIN; SET LOCAL lock_timeout='3s'; LOCK TABLE merchants IN ACCESS EXCLUSIVE MODE; SELECT 'locked'; SELECT pg_sleep(3); ROLLBACK;\n")
            lock.stdin.close()
            try:
                assert lock.stdout.readline().strip() == "locked", "deadline fixture did not acquire its bounded lock"
                grpc_check(True)
            finally:
                lock.wait(timeout=10)
                lock.stdout.close()
                lock.stderr.close()
            url = messaging + "/webhooks/whatsapp?" + urllib.parse.urlencode({"hub.mode": "subscribe", "hub.verify_token": "synthetic-verify-token", "hub.challenge": "123456"})
            with urllib.request.urlopen(url, timeout=5) as response:
                assert response.status == 200 and response.read() == b"123456"
            identifier, payload = inbound("HELP")
            before = sql("SELECT count(*) FROM webhook_events;")
            assert signed(payload, False)[0] == 401
            assert sql("SELECT count(*) FROM webhook_events;") == before
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                results = list(pool.map(lambda _: signed(payload), range(8)))
            assert all(code == 200 for code, _ in results)
            assert event_state(identifier) == "PROCESSED"
            captured = request(provider, "GET", "/")["requests"]
            assert len(captured) == 1 and "STOCK <SKU>" in captured[0]["body"]["text"]["body"]
            outbound = captured[0]["id"]
            assert sql(f"SELECT count(*) FROM message_records WHERE merchant_id='{merchant}' AND provider_message_id='{identifier}' AND direction='INBOUND';") == "1"
            assert sql(f"SELECT count(*) FROM message_records WHERE merchant_id='{merchant}' AND provider_message_id='{outbound}' AND direction='OUTBOUND';") == "1"
            correlation = captured[0]["body"]["biz_opaque_callback_data"]
            assert sql(f"SELECT count(*) FROM message_records WHERE correlation_id='{correlation}';") == "2"
            logs = compose("logs", "--since", "5m", "demand-service").stdout
            assert logs.count("Customer command processed requestId=" + correlation) == 1
            print("PASS verification, invalid signature isolation, signed HELP, persistence, correlation, eight concurrent duplicates: one Demand call and one outbound")

            now = int(time.time())
            for state, at in [("read", now), ("delivered", now - 1), ("sent", now - 2), ("failed", now - 3), ("read", now)]:
                assert signed(envelope({"statuses": [{"id": outbound, "status": state, "timestamp": str(at)}]}))[0] == 200
            assert sql(f"SELECT count(*) FROM delivery_events WHERE merchant_id='{merchant}' AND provider_message_id='{outbound}';") == "4"
            assert sql(f"SELECT status FROM message_records WHERE merchant_id='{merchant}' AND provider_message_id='{outbound}' AND direction='OUTBOUND';") == "READ"
            assert signed(envelope({"statuses": [{"id": "synthetic.unknown." + suffix, "status": "sent", "timestamp": str(now)}]}))[0] == 200
            print("PASS sent/delivered/read/failed status history, duplicate callback, unknown ID and chronological state")

            product = request(catalog, "POST", "/api/products", {"name": "Stage 3 test phone", "brand": "Demo", "category": "Phones"}, token, 201)
            sku = "STAGE3-" + suffix
            variant = request(catalog, "POST", f"/api/products/{product['id']}/variants", {"sku": sku, "attributes": {"color": "Black"}}, token, 201)
            _, payload = inbound("STOCK " + sku)
            assert signed(payload)[0] == 200
            assert request(provider, "GET", "/")["requests"][-1]["body"]["text"]["body"].endswith("currently out of stock.")
            waitlist = request(demand, "POST", "/api/waitlists", {"customerPhone": phone, "variantId": variant["id"], "consent": True}, token, expected=201)
            _, payload = inbound("STOP")
            assert signed(payload)[0] == 200
            assert request(demand, "GET", f"/api/waitlists/{waitlist['id']}", token=token)["status"] == "OPTED_OUT"
            request(demand, "POST", f"/api/customers/{waitlist['customerId']}/opt-in", token=token)
            renewed = request(demand, "POST", "/api/waitlists", {"customerPhone": phone, "variantId": variant["id"], "consent": False}, token, 201)
            identifier, delayed = inbound("STOP")
            delayed["entry"][0]["changes"][0]["value"]["messages"][0]["timestamp"] = str(int(time.time()) - 25 * 3600)
            sends_before_delayed = len(request(provider, "GET", "/")["requests"])
            assert signed(delayed)[0] == 200 and event_state(identifier) == "EXPIRED"
            assert request(demand, "GET", f"/api/waitlists/{renewed['id']}", token=token)["status"] == "OPTED_OUT"
            assert len(request(provider, "GET", "/")["requests"]) == sends_before_delayed
            request(catalog, "PATCH", f"/api/variants/{variant['id']}/inventory", {"quantity": 3}, token)
            _, payload = inbound("STOCK " + sku)
            assert signed(payload)[0] == 200
            assert request(provider, "GET", "/")["requests"][-1]["body"]["text"]["body"].endswith("currently available.")
            print("PASS exact SKU through production Catalog gRPC, authoritative zero/positive stock, STOP suppresses existing waitlist; delayed STOP updates consent without an expired-window reply")

            compose("stop", "catalog-service"); stopped_catalog = True
            _, payload = inbound("STOCK " + sku)
            assert signed(payload)[0] == 200
            assert "cannot verify stock" in request(provider, "GET", "/")["requests"][-1]["body"]["text"]["body"]
            compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "180", "catalog-service", timeout=210); stopped_catalog = False
            print("PASS Catalog outage: no guessed stock; safe fallback")

            compose("stop", "demand-service"); stopped_demand = True
            before_sends = len(request(provider, "GET", "/")["requests"])
            identifier, payload = inbound("HELP")
            start = time.monotonic()
            assert signed(payload)[0] == 200 and time.monotonic() - start < 8
            assert event_state(identifier) == "DEMAND_FAILED"
            assert len(request(provider, "GET", "/")["requests"]) == before_sends
            compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "180", "demand-service", timeout=210); stopped_demand = False
            # Reconnect after outage; the failed ID remains claimed, with no automatic replay.
            assert signed(payload)[0] == 200
            assert len(request(provider, "GET", "/")["requests"]) == before_sends
            print("PASS Demand outage: bounded failure persisted, no fabricated reply, no duplicate replay")

            time.sleep(3)  # Allow the existing Messaging channel to reconnect after the outage.
            request(provider, "POST", "/control", {"mode": "failure"})
            identifier, payload = inbound("HELP")
            assert signed(payload)[0] == 200
            assert event_state(identifier) == "PROVIDER_FAILED"
            assert sql(f"SELECT count(*) FROM message_records m JOIN webhook_events w ON w.id=m.webhook_event_id WHERE w.provider_event_id='inbound:{identifier}' AND m.direction='OUTBOUND' AND m.status='UNKNOWN' AND m.provider_message_id IS NULL;") == "1"
            assert signed(payload)[0] == 200
            assert len(request(provider, "GET", "/")["requests"]) == before_sends + 1
            print("PASS provider failure: uncertain state, no fabricated provider ID, one attempt")
        finally:
            if stopped_catalog:
                compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "180", "catalog-service", timeout=210)
            if stopped_demand:
                compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "180", "demand-service", timeout=210)
            compose("up", "-d", "--no-deps", "--wait", "--wait-timeout", "60", "messaging-service", timeout=90)
            docker("rm", "-f", provider_name)
    print("PASS local Stage 3 acceptance; real Meta acceptance remains separate; base Messaging configuration restored")


if __name__ == "__main__":
    main()
