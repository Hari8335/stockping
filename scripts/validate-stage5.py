#!/usr/bin/env python3
"""Stage 5 AI acceptance validation inside a disposable IsolatedStack.

Verifies:
1. Authenticated merchant AI query preview REST endpoint (/api/ai/query-preview).
2. Intent extraction, entity extraction (brand, model, attributes), and confidence scoring.
3. Natural-language, Singlish/mixed-language, ambiguous clarification, and opt-out understanding.
4. End-to-end WhatsApp flow: natural language query -> AI extraction -> Catalog stock check ->
   out-of-stock waitlist offer -> customer 'YES' reply -> waitlist creation -> 'STOP' opt-out.
5. All operations run deterministically on the mock/rule-based provider (enforced by the harness),
   without paid external AI credits, real WhatsApp traffic or any developer-stack data.

The validator never modifies records it did not create: instead of deactivating existing variants it
requires (read-only) that the isolated merchant has no other active variant of the model under test.
"""
import time
import urllib.parse
from validation_support import IsolatedStack, request


def main():
    with IsolatedStack("stage5", services=("catalog-service", "demand-service", "messaging-service")) as stack:
        session = stack.login()
        token, merchant = session["accessToken"], session["merchantId"]
        demand, catalog = stack.url("demand-service"), stack.url("catalog-service")

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
        phone = stack.phones.next()

        def sql_demand(statement):
            return stack.sql("demand", statement)

        def inbound(text):
            return stack.inbound(phone, text)

        # Step 1 (read-only precondition): exactly one active variant of the model will exist. Never mutate
        # pre-existing records; a fresh isolated merchant must have none.
        existing = request(catalog, "GET", "/api/products?" + urllib.parse.urlencode({"name": "Galaxy S25 Ultra"}), token=token)
        active = [v for p in existing.get("items", []) for v in request(catalog, "GET", f"/api/products/{p['id']}", token=token).get("variants", [])
                  if v.get("status") == "ACTIVE"]
        assert not active, f"isolated merchant unexpectedly has {len(active)} active 'Galaxy S25 Ultra' variant(s); refusing to modify them"

        # Create an out-of-stock product variant in Catalog
        prod = request(catalog, "POST", "/api/products", {"name": "Galaxy S25 Ultra", "brand": "Samsung", "category": "Phones"}, token, 201)
        sku = "SAM-S25U-" + stack.run_id
        request(catalog, "POST", f"/api/products/{prod['id']}/variants",
                {"sku": sku, "attributes": {"storage": "512GB", "color": "Black"}}, token, 201)

        # Step 2: Customer sends natural-language query to WhatsApp
        _, payload1 = inbound("s25u blk 512 thiyenawada")
        assert stack.signed(payload1)[0] == 200
        time.sleep(0.5)

        last_reply = stack.provider_requests()[-1]["body"]["text"]["body"]
        assert "out of stock" in last_reply.lower(), f"Expected out of stock reply, got: {last_reply}"
        assert "YES" in last_reply or "WAITLIST" in last_reply, f"Expected waitlist offer, got: {last_reply}"
        print("PASS inbound natural-language query received stock verification and restock waitlist offer")

        # Step 3: Customer replies 'YES' to join the waitlist
        _, payload2 = inbound("YES")
        assert stack.signed(payload2)[0] == 200
        time.sleep(0.5)

        confirmation = stack.provider_requests()[-1]["body"]["text"]["body"]
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
        assert stack.signed(payload3)[0] == 200
        time.sleep(0.5)

        consent_after = sql_demand(f"SELECT status FROM consent_records WHERE merchant_id='{merchant}' AND customer_id='{customer_row}' ORDER BY sequence DESC LIMIT 1;")
        assert consent_after == "OPT_OUT", f"Expected OPT_OUT consent after STOP, found {consent_after}"
        active_after = sql_demand(f"SELECT count(*) FROM waitlist_requests WHERE merchant_id='{merchant}' AND customer_id='{customer_row}' AND status='WAITING';")
        assert int(active_after) == 0, f"Expected 0 active waitlists after STOP, found {active_after}"
        print("PASS customer STOP opted out and cancelled active waitlists")

        print("\nALL STAGE 5 ACCEPTANCE CRITERIA PASSED SUCCESSFULLY!")


if __name__ == "__main__":
    main()
