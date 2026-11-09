#!/usr/bin/env python3
"""Stage 5 AI acceptance validation.

Verifies:
1. Authenticated merchant AI query preview REST endpoint (/api/ai/query-preview).
2. Intent extraction, entity extraction (brand, model, attributes), and confidence scoring.
3. Natural-language, Singlish/mixed-language, ambiguous clarification, and opt-out understanding.
4. End-to-end WhatsApp flow: natural language query -> AI extraction -> Catalog stock check ->
   out-of-stock waitlist offer -> customer 'YES' reply -> waitlist creation -> 'STOP' opt-out.
5. All operations run deterministically without consuming paid external AI credits.
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

    # ==========================================
    # 1. Validate Merchant AI Query Preview REST API
    # ==========================================
    print("--- 1. Testing Merchant AI Query Preview REST API ---")

    # 1a. Standard natural language inquiry
    res1 = request(demand, "POST", "/api/ai/query-preview", {"query": "S25 Ultra 512 black available?"}, token=token)
    assert res1["intent"] == "CHECK_STOCK", f"Expected CHECK_STOCK, got {res1['intent']}"
    assert res1["extracted"]["brand"] == "Samsung"
    assert res1["extracted"]["model"] == "Galaxy S25 Ultra"
    assert res1["extracted"]["attributes"].get("storage") == "512GB"
    assert res1["extracted"]["attributes"].get("color") == "Black"
    assert res1["confidence"] >= 0.70
    assert "advisory" in res1["disclaimer"].lower()
    print("PASS standard English query extraction:", res1["extracted"]["model"], res1["extracted"]["attributes"])

    # 1b. Mixed-language Singlish inquiry
    res2 = request(demand, "POST", "/api/ai/query-preview", {"query": "machan s25 ultra black 512 thiyenawada?"}, token=token)
    assert res2["intent"] == "CHECK_STOCK"
    assert res2["extracted"]["model"] == "Galaxy S25 Ultra"
    assert res2["confidence"] >= 0.70
    print("PASS mixed-language Singlish query extraction:", res2["extracted"]["model"])

    # 1c. Ambiguous query lacking model (must ask clarification without guessing a SKU)
    res3 = request(demand, "POST", "/api/ai/query-preview", {"query": "Do you have the silver 512 one?"}, token=token)
    assert res3["needsClarification"] is True or res3["catalogStatus"] in ("UNKNOWN", "AMBIGUOUS")
    assert res3["purpose"] in ("CLARIFICATION", "STOCK_AMBIGUOUS")
    assert len(res3["clarificationPrompt"]) > 0
    print("PASS ambiguous query requires clarification:", res3["clarificationPrompt"])

    # 1d. Natural language opt-out
    res4 = request(demand, "POST", "/api/ai/query-preview", {"query": "stop sending alerts"}, token=token)
    assert res4["intent"] == "OPT_OUT"
    assert res4["purpose"] == "OPT_OUT"
    print("PASS natural-language opt-out intent detected")

    # 1e. Help query
    res5 = request(demand, "POST", "/api/ai/query-preview", {"query": "help me with commands"}, token=token)
    assert res5["intent"] == "HELP"
    assert res5["purpose"] == "HELP"
    print("PASS natural-language help intent detected")

    # ==========================================
    # 2. End-to-End WhatsApp Scenario A (AI Query -> Waitlist Offer -> 'YES' Confirmation)
    # ==========================================
    print("\n--- 2. Testing End-to-End WhatsApp AI & Scenario A Flow ---")

    container = compose("ps", "-q", "messaging-service").stdout.strip()
    network = next(iter(json.loads(docker("inspect", container).stdout)[0]["NetworkSettings"]["Networks"]))
    suffix = uuid.uuid4().hex[:12]
    provider_name = "stockping-stage5-provider-" + suffix
    phone = "+9477" + str(uuid.uuid4().int % 10_000_000).zfill(7)
    app_secret = "synthetic-app-secret"
    base_file = str(ROOT / "docker-compose.yml")

    def sql_demand(statement):
        return compose("exec", "-T", "postgres", "sh", "-c",
                       'PGPASSWORD="$DEMAND_DB_PASSWORD" psql -h 127.0.0.1 -U demand_user -d demand_db -v ON_ERROR_STOP=1 -At',
                       input=statement).stdout.strip()

    def sql_messaging(statement):
        return compose("exec", "-T", "postgres", "sh", "-c",
                       'PGPASSWORD="$MESSAGING_DB_PASSWORD" psql -h 127.0.0.1 -U messaging_user -d messaging_db -v ON_ERROR_STOP=1 -At',
                       input=statement).stdout.strip()

    def signed(payload):
        data = json.dumps(payload).encode()
        signature = hmac.new(app_secret.encode(), data, hashlib.sha256).hexdigest()
        req = urllib.request.Request(messaging + "/webhooks/whatsapp", data=data, method="POST",
                                     headers={"Content-Type": "application/json", "X-Hub-Signature-256": "sha256=" + signature})
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status

    def envelope(value):
        return {"object": "whatsapp_business_account", "entry": [{"id": "100", "changes": [
            {"field": "messages", "value": {"messaging_product": "whatsapp", "metadata": {"phone_number_id": "200"}, **value}}]}]}

    def inbound(text, identifier=None):
        identifier = identifier or "synthetic.in." + uuid.uuid4().hex
        return identifier, envelope({"messages": [{"id": identifier, "from": phone[1:], "timestamp": str(int(time.time())),
                                                    "type": "text", "text": {"body": text}}]})

    with tempfile.TemporaryDirectory(prefix="stockping-stage5-") as tmp:
        override = Path(tmp) / "compose.json"
        override.write_text(json.dumps({"services": {"messaging-service": {"environment": {
            "STOCK_NOTIFICATIONS_ENABLED": "false", "WHATSAPP_ENABLED": "true", "WHATSAPP_VERIFY_TOKEN": "synthetic-verify-token",
            "WHATSAPP_APP_SECRET": app_secret, "WHATSAPP_ACCESS_TOKEN": "synthetic-access-token",
            "WHATSAPP_PHONE_NUMBER_ID": "200", "WHATSAPP_WABA_ID": "100", "WHATSAPP_GRAPH_API_VERSION": "v26.0",
            "WHATSAPP_MERCHANT_ID": merchant, "WHATSAPP_API_BASE_URL": f"http://{provider_name}:8080",
            "WHATSAPP_ALLOW_TEST_ENDPOINT": "true"}}}}))
        override.chmod(0o600)

        try:
            docker("run", "-d", "--name", provider_name, "--network", network, "-p", "127.0.0.1::8080",
                   "-v", f"{ROOT / 'scripts/fake-whatsapp.py'}:/server.py:ro", "python:3.13-alpine", "python", "/server.py")
            hostport = json.loads(docker("inspect", provider_name).stdout)[0]["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
            provider = "http://127.0.0.1:" + hostport

            compose("-f", base_file, "-f", str(override), "up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "60", "messaging-service", timeout=90)

            # Step 1: Deactivate any previous demo variants matching this model so exactly one active variant is tested
            existing = request(catalog, "GET", "/api/products?name=Galaxy%20S25%20Ultra", token=token)
            for p in existing.get("items", []):
                detail = request(catalog, "GET", f"/api/products/{p['id']}", token=token)
                for v in detail.get("variants", []):
                    if v.get("status") == "ACTIVE":
                        request(catalog, "PATCH", f"/api/variants/{v['id']}", {"status": "INACTIVE"}, token=token)

            # Create an out-of-stock product variant in Catalog
            prod = request(catalog, "POST", "/api/products", {"name": "Galaxy S25 Ultra", "brand": "Samsung", "category": "Phones"}, token, 201)
            sku = "SAM-S25U-" + suffix
            variant = request(catalog, "POST", f"/api/products/{prod['id']}/variants",
                              {"sku": sku, "attributes": {"storage": "512GB", "color": "Black"}}, token, 201)

            # Step 2: Customer sends natural-language query to WhatsApp
            _, payload1 = inbound("s25u blk 512 thiyenawada")
            assert signed(payload1) == 200
            time.sleep(0.5)

            captured = request(provider, "GET", "/")["requests"]
            last_reply = captured[-1]["body"]["text"]["body"]
            assert "out of stock" in last_reply.lower(), f"Expected out of stock reply, got: {last_reply}"
            assert "YES" in last_reply or "WAITLIST" in last_reply, f"Expected waitlist offer, got: {last_reply}"
            print("PASS inbound natural-language query received stock verification and restock waitlist offer")

            # Step 3: Customer replies 'YES' to join the waitlist
            _, payload2 = inbound("YES")
            assert signed(payload2) == 200
            time.sleep(0.5)

            captured2 = request(provider, "GET", "/")["requests"]
            confirmation = captured2[-1]["body"]["text"]["body"]
            assert "added to the waitlist" in confirmation.lower() or "waitlist" in confirmation.lower()
            print("PASS customer replied YES and received waitlist confirmation")

            # Step 4: Verify waitlist row is created in Demand DB
            customer_row = sql_demand(f"SELECT id FROM customers WHERE merchant_id='{merchant}' AND whatsapp_phone='{phone}';")
            assert customer_row, "Customer record must exist in Demand DB"
            waitlist_count = sql_demand(f"SELECT count(*) FROM waitlist_requests WHERE merchant_id='{merchant}' AND customer_id='{customer_row}' AND status='WAITING';")
            assert int(waitlist_count) == 1, f"Expected 1 WAITING request, found {waitlist_count}"
            consent_status = sql_demand(f"SELECT status FROM consent_records WHERE merchant_id='{merchant}' AND customer_id='{customer_row}' ORDER BY sequence DESC LIMIT 1;")
            assert consent_status == "OPT_IN", f"Expected OPT_IN consent, found {consent_status}"
            print("PASS Demand DB verified: customer created, OPT_IN consent recorded, WAITING waitlist created")

            # Step 5: Customer sends STOP to opt-out
            _, payload3 = inbound("STOP")
            assert signed(payload3) == 200
            time.sleep(0.5)

            consent_after = sql_demand(f"SELECT status FROM consent_records WHERE merchant_id='{merchant}' AND customer_id='{customer_row}' ORDER BY sequence DESC LIMIT 1;")
            assert consent_after == "OPT_OUT", f"Expected OPT_OUT consent after STOP, found {consent_after}"
            active_after = sql_demand(f"SELECT count(*) FROM waitlist_requests WHERE merchant_id='{merchant}' AND customer_id='{customer_row}' AND status='WAITING';")
            assert int(active_after) == 0, f"Expected 0 active waitlists after STOP, found {active_after}"
            print("PASS customer STOP opted out and cancelled active waitlists")

            print("\nALL STAGE 5 ACCEPTANCE CRITERIA PASSED SUCCESSFULLY!")

        finally:
            docker("rm", "-f", provider_name)
            compose("-f", base_file, "up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "60", "messaging-service", timeout=90)


if __name__ == "__main__":
    main()
