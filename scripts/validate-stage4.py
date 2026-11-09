#!/usr/bin/env python3
"""Isolated real Compose/PostgreSQL/RabbitMQ acceptance. Only a local fake provider is reachable.

Uses prebuilt stockping images, unique ports, a unique project and disposable volumes.
Never reads/writes root .env, changes the normal stack, or sends to Meta. Retains no credentials.
"""
import concurrent.futures
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from validation_support import ROOT, request


def poll(description, predicate, timeout=70):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.3)
    raise AssertionError("Timed out: " + description)


def main():
    run_id = uuid.uuid4().hex[:12]
    project = "stockping-stage4-" + run_id
    provider_name = project + "-provider"
    merchant = str(uuid.uuid4())
    values = {
        "POSTGRES_USER": "stage4_admin", "POSTGRES_PASSWORD": secrets.token_hex(24),
        "CATALOG_DB_PASSWORD": secrets.token_hex(24), "DEMAND_DB_PASSWORD": secrets.token_hex(24),
        "MESSAGING_DB_PASSWORD": secrets.token_hex(24), "RABBITMQ_USER": "stage4", "RABBITMQ_PASSWORD": secrets.token_hex(24),
        "AUTH_JWT_SECRET": secrets.token_hex(32), "MESSAGING_DEMAND_TOKEN": secrets.token_hex(32), "CATALOG_SERVICE_TOKEN": secrets.token_hex(32),
        "DEMAND_PROFILES": "demo", "DEMO_MERCHANT_ID": merchant, "DEMO_ADMIN_EMAIL": "stage4@demo.stockping.local", "DEMO_ADMIN_PASSWORD": secrets.token_hex(24),
        "STOCK_EVENTS_ENABLED": "true", "STOCK_NOTIFICATIONS_ENABLED": "true",
        "WHATSAPP_ENABLED": "true", "WHATSAPP_VERIFY_TOKEN": "synthetic-verify-token",
        "WHATSAPP_APP_SECRET": "synthetic-app-secret", "WHATSAPP_ACCESS_TOKEN": "synthetic-access-token",
        "WHATSAPP_PHONE_NUMBER_ID": "200", "WHATSAPP_WABA_ID": "100", "WHATSAPP_GRAPH_API_VERSION": "v26.0",
        "WHATSAPP_MERCHANT_ID": merchant, "WHATSAPP_API_BASE_URL": f"http://{provider_name}:8080",
        "WHATSAPP_ALLOW_TEST_ENDPOINT": "true", "WHATSAPP_STOCK_TEMPLATE_NAME": "synthetic_stock_test",
        "WHATSAPP_STOCK_TEMPLATE_LANGUAGE": "en_US", "WHATSAPP_STOCK_TEMPLATE_PARAMETERS": "product_name,sku,variant_description",
        "WHATSAPP_STOCK_REAL_SEND_ENABLED": "false", "WHATSAPP_STOCK_ALLOWED_RECIPIENTS": "",
    }
    # Reserve distinct host ports while selecting them; release immediately before Compose starts.
    sockets = []
    for key in ("FRONTEND_PORT", "CATALOG_PORT", "CATALOG_GRPC_PORT", "DEMAND_PORT", "MESSAGING_PORT", "POSTGRES_PORT", "RABBITMQ_PORT", "RABBITMQ_MANAGEMENT_PORT"):
        sock = socket.socket(); sock.bind(("127.0.0.1", 0)); sockets.append(sock)
        values[key] = str(sock.getsockname()[1])
    env = {**os.environ, **values}
    demand = "http://127.0.0.1:" + values["DEMAND_PORT"]
    catalog = "http://127.0.0.1:" + values["CATALOG_PORT"]
    messaging = "http://127.0.0.1:" + values["MESSAGING_PORT"]
    broker = "http://127.0.0.1:" + values["RABBITMQ_MANAGEMENT_PORT"]

    def docker(*args, timeout=120, **kwargs):
        return subprocess.run(["docker", *args], capture_output=True, text=True, check=True, timeout=timeout, env=env, **kwargs)

    with tempfile.TemporaryDirectory(prefix="stockping-stage4-") as tmp:
        env_file, override = Path(tmp) / "test.env", Path(tmp) / "compose.json"
        env_file.write_text("\n".join(f"{k}={v}" for k, v in values.items()) + "\n"); env_file.chmod(0o600)
        override_data = {"services": {service: {"image": "stockping-" + service + ":latest"} for service in ("catalog-service", "demand-service", "messaging-service")}}
        override_data["services"]["messaging-service"]["environment"] = {}
        override.write_text(json.dumps(override_data)); override.chmod(0o600)

        def compose(*args, timeout=120, **kwargs):
            return docker("compose", "--project-directory", str(ROOT), "--project-name", project, "--env-file", str(env_file),
                          "-f", str(ROOT / "docker-compose.yml"), "-f", str(override), *args, timeout=timeout, **kwargs)

        def sql(owner, statement):
            return compose("exec", "-T", "postgres", "sh", "-c",
                           f'PGPASSWORD="${owner.upper()}_DB_PASSWORD" psql -h 127.0.0.1 -U {owner}_user -d {owner}_db -v ON_ERROR_STOP=1 -At', input=statement).stdout.strip()

        def rabbit(method, path, body=None):
            import base64
            credential = base64.b64encode((values["RABBITMQ_USER"] + ":" + values["RABBITMQ_PASSWORD"]).encode()).decode()
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
            override_data["services"]["messaging-service"]["environment"].update(settings)
            override.write_text(json.dumps(override_data))
            compose("up", "-d", "--no-deps", "--no-build", "--wait", "--wait-timeout", "60", "messaging-service", timeout=90)

        def signed(value):
            payload = {"object": "whatsapp_business_account", "entry": [{"id": "100", "changes": [{"field": "messages", "value": {
                "messaging_product": "whatsapp", "metadata": {"phone_number_id": "200"}, **value}}]}]}
            data = json.dumps(payload).encode()
            signature = hmac.new(values["WHATSAPP_APP_SECRET"].encode(), data, hashlib.sha256).hexdigest()
            req = urllib.request.Request(messaging + "/webhooks/whatsapp", data=data, method="POST", headers={"Content-Type": "application/json", "X-Hub-Signature-256": "sha256=" + signature})
            with urllib.request.urlopen(req, timeout=15) as response:
                assert response.status == 200

        try:
            for sock in sockets:
                sock.close()
            compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "180", "postgres", "rabbitmq", "catalog-service", "demand-service", "messaging-service", timeout=240)
            postgres = compose("ps", "-q", "postgres").stdout.strip()
            network = next(iter(json.loads(docker("inspect", postgres).stdout)[0]["NetworkSettings"]["Networks"]))
            docker("run", "-d", "--name", provider_name, "--network", network, "-p", "127.0.0.1::8080",
                   "-v", f"{ROOT / 'scripts/fake-whatsapp.py'}:/server.py:ro", "python:3.13-alpine", "python", "/server.py")
            provider_port = json.loads(docker("inspect", provider_name).stdout)[0]["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
            provider = "http://127.0.0.1:" + provider_port
            poll("fake provider ready", lambda: request(provider, "GET", "/"))
            session = request(demand, "POST", "/api/auth/login", {"email": values["DEMO_ADMIN_EMAIL"], "password": values["DEMO_ADMIN_PASSWORD"]})
            token = session["accessToken"]

            def fixture(label, owner=token, phone=None):
                product = request(catalog, "POST", "/api/products", {"name": "Stage 4 " + label, "brand": "Synthetic", "category": "Tests"}, owner, 201)
                variant = request(catalog, "POST", f"/api/products/{product['id']}/variants", {"sku": "S4-" + uuid.uuid4().hex[:12], "attributes": {"color": "Black"}}, owner, 201)
                phone = phone or "+1999" + str(uuid.uuid4().int % 10_000_000).zfill(7)
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
            compose("restart", "messaging-service"); poll("Messaging restarted", lambda: request(messaging, "GET", "/health"))
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
            for index in range(2):
                request(demand, "POST", "/api/waitlists", {"customerPhone": f"+1999000000{index}", "variantId": multi["id"], "consent": True}, token, 201)
            multiple = inventory(multi, 4)["stockEventId"]; completed(multiple, count=3)
            assert len(sends()) == 4
            print("PASS all three eligible waiters receive separate durable notifications")

            # Second tenant uses authenticated REST and its own customer, with the same synthetic phone.
            tenant, user, email = str(uuid.uuid4()), str(uuid.uuid4()), "other-" + run_id + "@demo.stockping.local"
            sql("demand", f"""BEGIN; INSERT INTO merchants(id,name,timezone,status,created_at,updated_at) VALUES('{tenant}','Isolated validation tenant','UTC','ACTIVE',now(),now());
                INSERT INTO users(id,merchant_id,email,password_hash,role,status,created_at,updated_at)
                SELECT '{user}','{tenant}','{email}',password_hash,'MERCHANT_ADMIN','ACTIVE',now(),now() FROM users WHERE id='{session['userId']}'; COMMIT;""")
            other_token = request(demand, "POST", "/api/auth/login", {"email": email, "password": values["DEMO_ADMIN_PASSWORD"]})["accessToken"]
            other_variant, _, _ = fixture("other merchant", other_token, phone)
            foreign = inventory(other_variant, 1, other_token)["stockEventId"]
            foreign_job = completed(foreign, "SKIPPED")[0]
            assert foreign_job["reason"] == "channel_unmapped" and len(sends()) == 4
            assert all(row["id"] != foreign_job["id"] for row in request(demand, "GET", "/api/notifications", token=token))
            print("PASS tenant isolation and single-channel merchant mapping prevent cross-merchant sending")

            for mode, status in (("rejected", "FAILED"), ("failure", "UNKNOWN")):
                failing, _, _ = fixture(mode)
                request(provider, "POST", "/control", {"mode": mode})
                failed_event = inventory(failing, 2)["stockEventId"]
                failed_job = completed(failed_event, status)[0]
                count = len(sends())
                replay("notification.requested.v1", json.loads(sql("demand", f"SELECT payload FROM event_outbox WHERE id='{failed_job['id']}';")))
                poll("failed replay acknowledged", lambda: rabbit("GET", "queues/%2F/messaging.notification-requested.v1").get("messages", 1) == 0)
                assert len(sends()) == count
            request(provider, "POST", "/control", {"mode": "success"})
            print("PASS provider rejection and uncertain 503 outcomes persist separately; neither is blindly resent")

            outage_variant, _, _ = fixture("broker recovery")
            compose("stop", "rabbitmq")
            outage_event = inventory(outage_variant, 6)["stockEventId"]
            assert sql("catalog", f"SELECT status FROM event_outbox WHERE id='{outage_event}';") == "PENDING"
            assert request(catalog, "GET", f"/api/variants/{outage_variant['id']}/inventory", token=token)["quantity"] == 6
            compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "120", "rabbitmq", timeout=150)
            completed(outage_event)
            print("PASS broker outage preserves committed stock/outbox; reconnect publishes and delivers after recovery")

            # A valid committed event is retried while its authoritative dependency is unavailable.
            retry_variant, _, _ = fixture("bounded retry")
            compose("stop", "demand-service")
            retry_event = inventory(retry_variant, 2)["stockEventId"]
            poll("retry fixture published", lambda: sql("catalog", f"SELECT status FROM event_outbox WHERE id='{retry_event}';") == "PUBLISHED")
            payload = event_payload(retry_event)
            compose("stop", "catalog-service")
            compose("up", "-d", "--no-deps", "--no-build", "--wait", "--wait-timeout", "90", "demand-service", timeout=120)
            dead = "queues/%2F/demand.stock-available.v1.dead"
            poll("bounded transient retry dead letter", lambda: rabbit("GET", dead).get("messages_ready", 0) >= 1, timeout=90)
            assert not jobs(retry_event)
            compose("up", "-d", "--no-deps", "--no-build", "--wait", "--wait-timeout", "90", "catalog-service", timeout=120)
            replay("inventory.stock_available.v1", payload); completed(retry_event)
            replay("inventory.stock_available.v1", {**payload, "eventId": str(uuid.uuid4()), "eventVersion": 99})
            poll("invalid version dead letter", lambda: rabbit("GET", dead).get("messages_ready", 0) >= 2)
            replay("notification.requested.v1", {"eventVersion": 99})
            poll("notification poison dead letter", lambda: rabbit("GET", "queues/%2F/messaging.notification-requested.v1.dead").get("messages_ready", 0) >= 1)
            print("PASS bounded retry -> dead letter during Catalog outage, safe operational replay, and poison-event dead lettering on both routes")
            assert all(r["body"]["type"] in ("text", "template") for r in request(provider, "GET", "/")["requests"])
            print(f"PASS Stage 4 acceptance: {len(sends())} fake template attempts; no Meta credentials or real-provider traffic")
        except Exception:
            diagnostics = Path(tempfile.gettempdir()) / (project + "-diagnostics.log")
            diagnostics.write_text(compose("logs", "--tail", "120").stdout)
            diagnostics.chmod(0o600)
            print("Failure diagnostics: " + str(diagnostics), flush=True)
            raise
        finally:
            # These names are generated here, never user project names/volumes.
            try:
                docker("rm", "-f", provider_name)
            except subprocess.CalledProcessError:
                pass
            assert project.startswith("stockping-stage4-") and len(run_id) == 12
            compose("down", "--volumes", "--remove-orphans", timeout=120)
            print("RESTORED isolated test containers/volumes removed; normal Compose configuration and data untouched")


if __name__ == "__main__":
    main()
