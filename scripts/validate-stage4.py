#!/usr/bin/env python3
"""Isolated real Compose/PostgreSQL/RabbitMQ acceptance. Only a local fake provider is reachable.

Runs in a disposable IsolatedStack (scripts/validation_support.py): images built from the working tree,
a unique project, synthetic credentials and phones, the fake provider, and disposable volumes. Never
reads the root .env or inherited shell credentials, never changes the developer stack, never sends to Meta.
"""
import base64
import concurrent.futures
import json
import time
import urllib.request
import uuid
from validation_support import IsolatedStack, poll, request


def main():
    with IsolatedStack("stage4", services=("catalog-service", "demand-service", "messaging-service"), stock_notifications=True,
                       stock_template=("synthetic_stock_test", "en_US", "product_name,sku,variant_description")) as stack:
        demand, catalog, messaging = stack.url("demand-service"), stack.url("catalog-service"), stack.url("messaging-service")
        broker = stack.url("rabbitmq", 1)
        provider = stack.provider
        run_id = stack.run_id

        def sql(owner, statement):
            return stack.sql(owner, statement)

        def rabbit(method, path, body=None):
            credential = base64.b64encode((stack.values["RABBITMQ_USER"] + ":" + stack.values["RABBITMQ_PASSWORD"]).encode()).decode()
            req = urllib.request.Request(broker + "/api/" + path, data=None if body is None else json.dumps(body).encode(), method=method,
                                         headers={"Content-Type": "application/json", "Authorization": "Basic " + credential})
            with urllib.request.urlopen(req, timeout=8) as response:
                data = response.read()
                return json.loads(data) if data else None

        def replay(routing, payload):
            assert rabbit("POST", "exchanges/%2F/stockping.events/publish", {
                "properties": {"delivery_mode": 2, "content_type": "application/json", "message_id": payload.get("eventId", str(uuid.uuid4()))},
                "routing_key": routing, "payload": json.dumps(payload), "payload_encoding": "string"})["routed"]

        def reconfigure(**settings):
            stack.reconfigure_messaging(**settings)

        def signed(value):
            assert stack.signed(stack.envelope(value))[0] == 200

        session = stack.login()
        token = session["accessToken"]

        def fixture(label, owner=token, phone=None):
            product = request(catalog, "POST", "/api/products", {"name": "Stage 4 " + label, "brand": "Synthetic", "category": "Tests"}, owner, 201)
            variant = request(catalog, "POST", f"/api/products/{product['id']}/variants", {"sku": "S4-" + uuid.uuid4().hex[:12], "attributes": {"color": "Black"}}, owner, 201)
            phone = phone or stack.phones.next()
            def create_waiter():
                result = request(demand, "POST", "/api/waitlists", {"customerPhone": phone, "variantId": variant["id"], "consent": True}, owner, (201, 503, 504))
                # Catalog connection/JIT warm-up may exceed its first deadline. These failures roll back the whole request.
                return result if "id" in result else False
            waiter = poll("authenticated Catalog RPC readiness", create_waiter, timeout=30)
            return variant, waiter, phone

        def inventory(variant, quantity, owner=token):
            return request(catalog, "PATCH", f"/api/variants/{variant['id']}/inventory", {"quantity": quantity}, owner)

        def event_payload(event_id):
            return json.loads(sql("catalog", f"SELECT payload FROM event_outbox WHERE id='{event_id}';"))

        def jobs(event_id):
            data = sql("demand", f"SELECT coalesce(json_agg(row_to_json(n)),'[]') FROM notification_jobs n WHERE availability_event_id='{event_id}';")
            return json.loads(data)

        def completed(event_id, status="ACCEPTED", count=1):
            return poll("notification " + status, lambda: (rows if len(rows := jobs(event_id)) == count and all(r["status"] == status for r in rows) else False))

        def sends():
            return [r for r in request(provider, "GET", "/")["requests"] if r["body"]["type"] == "template"]

        variant, waiter, phone = fixture("primary")
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            changes = list(pool.map(lambda _: inventory(variant, 5), range(4)))
        ids = [r["stockEventId"] for r in changes if r["changed"]]
        assert len(ids) == 1
        event_id = ids[0]
        job = completed(event_id)[0]
        assert sql("catalog", f"SELECT status FROM event_outbox WHERE id='{event_id}';") == "PUBLISHED"
        assert sql("demand", f"SELECT outcome FROM availability_events WHERE id='{event_id}';") == "PROCESSED"
        assert sql("messaging", f"SELECT status||':'||attempts FROM notification_receipts WHERE id='{job['id']}';") == "ACCEPTED:1"
        captured = sends(); assert len(captured) == 1
        template = captured[0]["body"]["template"]
        assert template["name"] == "synthetic_stock_test" and template["language"]["code"] == "en_US"
        assert [p["text"] for p in template["components"][0]["parameters"]] == ["Stage 4 primary", variant["sku"], "color: Black"]
        assert captured[0]["id"] == job["provider_message_id"]
        print("PASS concurrent inventory -> committed outbox -> RabbitMQ -> Demand -> exactly one fake template submission; all three database states")

        replay("inventory.stock_available.v1", event_payload(event_id))
        notification = json.loads(sql("demand", f"SELECT payload FROM event_outbox WHERE id='{job['id']}';"))
        for _ in range(3):
            replay("notification.requested.v1", notification)
        poll("duplicate requests acknowledged", lambda: rabbit("GET", "queues/%2F/messaging.notification-requested.v1").get("messages", 1) == 0)
        stack.compose("restart", "messaging-service"); poll("Messaging restarted", lambda: request(messaging, "GET", "/health"))
        replay("notification.requested.v1", notification)
        poll("restart replay acknowledged", lambda: rabbit("GET", "queues/%2F/messaging.notification-requested.v1").get("messages", 1) == 0)
        assert len(sends()) == 1 and len(jobs(event_id)) == 1
        signed({"statuses": [{"id": job["provider_message_id"], "status": "read", "timestamp": str(int(time.time()))}]})
        assert sql("messaging", f"SELECT status FROM message_records WHERE notification_id='{job['id']}';") == "READ"
        print("PASS event/request replay and consumer restart do not repeat a send; template READ callback reconciles")

        assert not inventory(variant, 8)["restockTransition"]
        assert not inventory(variant, 0)["restockTransition"]
        later = inventory(variant, 2)["stockEventId"]
        poll("later restock processed", lambda: sql("demand", f"SELECT count(*) FROM availability_events WHERE id='{later}';") == "1")
        assert later != event_id and not jobs(later) and len(sends()) == 1
        print("PASS positive changes/depletion produce no availability event; later restock is distinct; already notified waitlist suppressed")

        variant2, waiter2, phone2 = fixture("queued STOP")
        reconfigure(WHATSAPP_STOCK_TEMPLATE_NAME="")
        assert request(messaging, "GET", "/health/notifications")["status"] == "template_configuration_required"
        stopped_event = inventory(variant2, 3)["stockEventId"]
        poll("queued job without template", lambda: len(jobs(stopped_event)) == 1)
        assert len(sends()) == 1
        signed({"messages": [{"id": "synthetic.stop." + run_id, "from": phone2[1:], "timestamp": str(int(time.time())), "type": "text", "text": {"body": "STOP"}}]})
        assert request(demand, "GET", f"/api/waitlists/{waiter2['id']}", token=token)["status"] == "OPTED_OUT"
        reconfigure(WHATSAPP_STOCK_TEMPLATE_NAME="synthetic_stock_test")
        completed(stopped_event, "SKIPPED")
        inventory(variant2, 0); after_stop = inventory(variant2, 1)["stockEventId"]
        poll("post-STOP event processed", lambda: sql("demand", f"SELECT count(*) FROM availability_events WHERE id='{after_stop}';") == "1")
        assert not jobs(after_stop) and len(sends()) == 1
        print("PASS missing-template operational gate, queued STOP revalidation, and future opt-out suppression")

        multi, _, _ = fixture("multiple")
        for _ in range(2):
            request(demand, "POST", "/api/waitlists", {"customerPhone": stack.phones.next(), "variantId": multi["id"], "consent": True}, token, 201)
        multiple = inventory(multi, 4)["stockEventId"]; completed(multiple, count=3)
        assert len(sends()) == 4
        print("PASS all three eligible waiters receive separate durable notifications")

        # Second tenant uses authenticated REST and its own customer, with the same synthetic phone.
        # Its administrator has its own random password; no credential hash is copied.
        other_token = stack.create_tenant("Isolated validation tenant")["token"]
        other_variant, _, _ = fixture("other merchant", other_token, phone)
        foreign = inventory(other_variant, 1, other_token)["stockEventId"]
        foreign_job = completed(foreign, "SKIPPED")[0]
        assert foreign_job["reason"] == "channel_unmapped" and len(sends()) == 4
        assert all(row["id"] != foreign_job["id"] for row in request(demand, "GET", "/api/notifications", token=token))
        print("PASS tenant isolation and single-channel merchant mapping prevent cross-merchant sending")

        for mode, status in (("rejected", "FAILED"), ("failure", "UNKNOWN")):
            failing, _, _ = fixture(mode)
            stack.provider_mode(mode)
            failed_event = inventory(failing, 2)["stockEventId"]
            failed_job = completed(failed_event, status)[0]
            count = len(sends())
            replay("notification.requested.v1", json.loads(sql("demand", f"SELECT payload FROM event_outbox WHERE id='{failed_job['id']}';")))
            poll("failed replay acknowledged", lambda: rabbit("GET", "queues/%2F/messaging.notification-requested.v1").get("messages", 1) == 0)
            assert len(sends()) == count
        stack.provider_mode("success")
        print("PASS provider rejection and uncertain 503 outcomes persist separately; neither is blindly resent")

        outage_variant, _, _ = fixture("broker recovery")
        stack.stop("rabbitmq")
        outage_event = inventory(outage_variant, 6)["stockEventId"]
        assert sql("catalog", f"SELECT status FROM event_outbox WHERE id='{outage_event}';") == "PENDING"
        assert request(catalog, "GET", f"/api/variants/{outage_variant['id']}/inventory", token=token)["quantity"] == 6
        stack.start("rabbitmq", 120)
        completed(outage_event)
        print("PASS broker outage preserves committed stock/outbox; reconnect publishes and delivers after recovery")

        # A valid committed event is retried while its authoritative dependency is unavailable.
        retry_variant, _, _ = fixture("bounded retry")
        stack.stop("demand-service")
        retry_event = inventory(retry_variant, 2)["stockEventId"]
        poll("retry fixture published", lambda: sql("catalog", f"SELECT status FROM event_outbox WHERE id='{retry_event}';") == "PUBLISHED")
        payload = event_payload(retry_event)
        stack.stop("catalog-service")
        stack.start("demand-service", 90)
        dead = "queues/%2F/demand.stock-available.v1.dead"
        poll("bounded transient retry dead letter", lambda: rabbit("GET", dead).get("messages_ready", 0) >= 1, timeout=90)
        assert not jobs(retry_event)
        stack.start("catalog-service", 90)
        # Demand's gRPC channel may still be in reconnect backoff after the outage; replaying before it answers
        # would exhaust the delivery limit and dead-letter the operational replay. Wait for the real path.
        stack.await_catalog_rpc()
        replay("inventory.stock_available.v1", payload); completed(retry_event)
        replay("inventory.stock_available.v1", {**payload, "eventId": str(uuid.uuid4()), "eventVersion": 99})
        poll("invalid version dead letter", lambda: rabbit("GET", dead).get("messages_ready", 0) >= 2)
        replay("notification.requested.v1", {"eventVersion": 99})
        poll("notification poison dead letter", lambda: rabbit("GET", "queues/%2F/messaging.notification-requested.v1.dead").get("messages_ready", 0) >= 1)
        print("PASS bounded retry -> dead letter during Catalog outage, safe operational replay, and poison-event dead lettering on both routes")
        assert all(r["body"]["type"] in ("text", "template") for r in request(provider, "GET", "/")["requests"])
        print(f"PASS Stage 4 acceptance: {len(sends())} fake template attempts; no Meta credentials or real-provider traffic")
    print("RESTORED isolated test containers/volumes removed; normal Compose configuration and data untouched")


if __name__ == "__main__":
    main()
