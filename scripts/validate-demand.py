#!/usr/bin/env python3
"""Live Stage 2 acceptance using real services inside a disposable IsolatedStack.

A second tenant is created inside the isolated database with its own random password (never a copied
credential hash). All business operations use authenticated REST; Demand verifies Catalog via gRPC.
Stops/restarts the isolated Catalog to prove failure safety. Nothing persists after the run.
"""
import time
from validation_support import IsolatedStack, request


def main():
    with IsolatedStack("demand", services=("catalog-service", "demand-service", "frontend")) as stack:
        demand, catalog, frontend = stack.url("demand-service"), stack.url("catalog-service"), stack.url("frontend")
        session = stack.login()
        token = session["accessToken"]
        request(demand, "POST", "/api/auth/login", {"email": stack.admin_email, "password": "wrong-password"}, expected=401)
        request(demand, "GET", "/api/waitlists", expected=401)
        request(catalog, "GET", "/api/products", expected=401)
        request(frontend, "GET", "/api/waitlists", token=token)  # Same-origin browser proxy.
        print("PASS authentication: login, bad password, anonymous rejection, authenticated browser proxy")

        tenant_b = stack.create_tenant("Stage 2 validation tenant")
        merchant_b, token_b = tenant_b["merchantId"], tenant_b["token"]
        request(demand, "POST", "/api/auth/login", {"email": tenant_b["email"], "password": stack.admin_password}, expected=401)

        def variant(owner, sku):
            product = request(catalog, "POST", "/api/products", {"name": "Stage 2 demo phone", "brand": "Demo", "category": "Phones"}, owner, 201)
            return request(catalog, "POST", f"/api/products/{product['id']}/variants", {"sku": sku, "attributes": {"color": "Black"}}, owner, 201)["id"]
        suffix = stack.run_id
        variant_a = variant(token, "DEMAND-A-" + suffix)
        variant_b = variant(token_b, "DEMAND-B-" + suffix)
        phone = stack.phones.next()
        body = {"customerPhone": phone, "displayName": "Stage 2 Demo Customer", "variantId": variant_a, "consent": True}
        before = request(demand, "GET", "/api/analytics/demand", token=token)["totalActive"]
        waitlist = request(demand, "POST", "/api/waitlists", body, token, 201)
        assert waitlist["status"] == "WAITING" and waitlist["eligible"]
        duplicate = request(demand, "POST", "/api/waitlists", body, token, 409)
        assert duplicate["code"] == "DUPLICATE_ACTIVE_WAITLIST"
        request(demand, "POST", "/api/waitlists", {**body, "merchantId": merchant_b}, token, 400)
        cross = request(demand, "POST", "/api/waitlists", {**body, "variantId": variant_b}, token, 404)
        assert cross["code"] == "VARIANT_NOT_FOUND"
        assert request(demand, "GET", "/api/analytics/demand", token=token)["totalActive"] == before + 1
        other = request(demand, "POST", "/api/waitlists", {**body, "variantId": variant_b}, token_b, 201)
        assert other["customerId"] != waitlist["customerId"] and other["customerPhone"] == phone
        request(demand, "GET", f"/api/waitlists/{other['id']}", token=token, expected=404)
        for action in ("opt-out", "opt-in"):
            request(demand, "POST", f"/api/customers/{other['customerId']}/{action}", token=token, expected=404)
        assert request(demand, "GET", f"/api/waitlists?customerId={other['customerId']}&merchantId={merchant_b}", token=token)["totalElements"] == 0
        print("PASS waitlists: real Catalog gRPC resolution/zero stock, WAITING, duplicate prevention, counts, tenant and phone isolation")

        available = variant(token, "DEMAND-AVAILABLE-" + suffix)
        request(catalog, "PATCH", f"/api/variants/{available}/inventory", {"quantity": 2}, token)
        assert request(demand, "POST", "/api/waitlists", {**body, "variantId": available}, token, 409)["code"] == "ITEM_AVAILABLE"
        out = request(demand, "POST", f"/api/customers/{waitlist['customerId']}/opt-out", token=token)
        assert out["status"] == "OPT_OUT" and out["updatedWaitlists"] == 1
        detail = request(demand, "GET", f"/api/waitlists/{waitlist['id']}", token=token)
        assert detail["status"] == "OPTED_OUT" and not detail["eligible"]
        assert request(demand, "POST", "/api/waitlists", body, token, 409)["code"] == "CUSTOMER_OPTED_OUT"
        assert request(demand, "GET", "/api/analytics/demand", token=token)["totalActive"] == before
        history = stack.sql("demand", f"SELECT string_agg(status, ',' ORDER BY sequence) FROM consent_records WHERE customer_id='{waitlist['customerId']}';")
        assert history.strip() == "OPT_IN,OPT_OUT"
        request(demand, "POST", f"/api/customers/{waitlist['customerId']}/opt-in", token=token)
        renewed = request(demand, "POST", "/api/waitlists", {**body, "consent": False}, token, 201)
        assert renewed["id"] != waitlist["id"]
        print("PASS consent: ordered opt-in/opt-out history, eligibility suppression, explicit re-consent, available-stock rejection")

        count_before_outage = stack.sql("demand", "SELECT count(*) FROM waitlist_requests;").strip()
        outage_phone = stack.phones.next()
        try:
            stack.stop("catalog-service")
            # TCP disconnect detection can race the bounded RPC deadline. Both mappings
            # are safe and tested independently in CatalogClientTest.
            started = time.monotonic()
            failure = request(demand, "POST", "/api/waitlists", {**body, "customerPhone": outage_phone}, token, (503, 504))
            assert failure["code"] in ("CATALOG_UNAVAILABLE", "CATALOG_TIMEOUT")
            assert time.monotonic() - started < 10, "Catalog failure was not bounded"
            assert stack.sql("demand", "SELECT count(*) FROM waitlist_requests;").strip() == count_before_outage
            assert stack.sql("demand", f"SELECT count(*) FROM customers WHERE merchant_id='{session['merchantId']}' AND whatsapp_phone='{outage_phone}';").strip() == "0"
        finally:
            stack.start("catalog-service")
        for _ in range(25):
            try:
                recovered = request(demand, "POST", "/api/waitlists", {**body, "customerPhone": outage_phone}, token, 201)
                break
            except AssertionError:
                time.sleep(1)
        else:
            raise AssertionError("Catalog did not recover")
        assert recovered["status"] == "WAITING"
        print(f"PASS Catalog outage: safe {failure['code']}, bounded response, no partial records, recovery through gRPC")
        request(demand, "POST", "/api/auth/logout", token=token, expected=204)
        request(demand, "GET", "/api/waitlists", token=token)  # Documented stateless JWT limitation.
        request(demand, "GET", "/api/waitlists", expected=401)
        print("PASS logout: acknowledgement, client deletion behavior, issued token remains valid until expiry")
        print(f"PASS Stage 2 acceptance; demo variant={variant_a}; demo waitlist={renewed['id']}")


if __name__ == "__main__":
    main()
