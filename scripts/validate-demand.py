#!/usr/bin/env python3
"""Live Stage 2 acceptance using both real services. Leaves synthetic demo records.

Creates a second demo tenant through demand_user solely as a validation fixture.
All business operations use authenticated REST; Demand verifies Catalog via gRPC.
Briefly stops/restarts Catalog to prove failure safety, preserving all volumes.
"""
import secrets
import time
import uuid
from validation_support import compose, endpoint, login, request, services


def main():
    config = services()
    demand = endpoint(config, "demand-service")
    catalog = endpoint(config, "catalog-service")
    frontend = endpoint(config, "frontend")
    demo = config["demand-service"]["environment"]
    session = login(config)
    token = session["accessToken"]
    request(demand, "POST", "/api/auth/login", {"email": demo["DEMO_ADMIN_EMAIL"], "password": "wrong-password"}, expected=401)
    request(demand, "GET", "/api/waitlists", expected=401)
    request(catalog, "GET", "/api/products", expected=401)
    request(frontend, "GET", "/api/waitlists", token=token)  # Same-origin browser proxy.
    print("PASS authentication: login, bad password, anonymous rejection, authenticated browser proxy")

    merchant_b, user_b = str(uuid.uuid4()), str(uuid.uuid4())
    email_b = f"validation-{uuid.uuid4().hex}@demo.stockping.local"
    # Copy only a demo hash; never print or interpolate the password into SQL/arguments.
    sql = f"""
    BEGIN;
    INSERT INTO merchants(id,name,timezone,status,created_at,updated_at)
      VALUES ('{merchant_b}','Stage 2 validation tenant','UTC','ACTIVE',now(),now());
    INSERT INTO users(id,merchant_id,email,password_hash,role,status,created_at,updated_at)
      SELECT '{user_b}','{merchant_b}','{email_b}',password_hash,'MERCHANT_ADMIN','ACTIVE',now(),now()
      FROM users WHERE id='{session['userId']}';
    COMMIT;
    """
    def demand_sql(sql_text):
        return compose("exec", "-T", "postgres", "sh", "-c",
                       'PGPASSWORD="$DEMAND_DB_PASSWORD" psql -h 127.0.0.1 -U demand_user -d demand_db -v ON_ERROR_STOP=1 -At', input=sql_text).stdout
    demand_sql(sql)
    token_b = request(demand, "POST", "/api/auth/login", {"email": email_b, "password": demo["DEMO_ADMIN_PASSWORD"]})["accessToken"]

    def variant(owner, sku):
        product = request(catalog, "POST", "/api/products", {"name": "Stage 2 demo phone", "brand": "Demo", "category": "Phones"}, owner, 201)
        return request(catalog, "POST", f"/api/products/{product['id']}/variants", {"sku": sku, "attributes": {"color": "Black"}}, owner, 201)["id"]
    suffix = uuid.uuid4().hex[:12]
    variant_a = variant(token, "DEMAND-A-" + suffix)
    variant_b = variant(token_b, "DEMAND-B-" + suffix)
    phone = "+9477" + "".join(str(secrets.randbelow(10)) for _ in range(7))
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
    history = demand_sql(f"SELECT string_agg(status, ',' ORDER BY sequence) FROM consent_records WHERE customer_id='{waitlist['customerId']}';")
    assert history.strip() == "OPT_IN,OPT_OUT"
    request(demand, "POST", f"/api/customers/{waitlist['customerId']}/opt-in", token=token)
    renewed = request(demand, "POST", "/api/waitlists", {**body, "consent": False}, token, 201)
    assert renewed["id"] != waitlist["id"]
    print("PASS consent: ordered opt-in/opt-out history, eligibility suppression, explicit re-consent, available-stock rejection")

    count_before_outage = demand_sql("SELECT count(*) FROM waitlist_requests;").strip()
    try:
        compose("stop", "catalog-service")
        outage_phone = "+9478" + "".join(str(secrets.randbelow(10)) for _ in range(7))
        # TCP disconnect detection can race the bounded RPC deadline. Both mappings
        # are safe and tested independently in CatalogClientTest.
        started = time.monotonic()
        failure = request(demand, "POST", "/api/waitlists", {**body, "customerPhone": outage_phone}, token, (503, 504))
        assert failure["code"] in ("CATALOG_UNAVAILABLE", "CATALOG_TIMEOUT")
        assert time.monotonic() - started < 10, "Catalog failure was not bounded"
        assert demand_sql("SELECT count(*) FROM waitlist_requests;").strip() == count_before_outage
        assert demand_sql(f"SELECT count(*) FROM customers WHERE merchant_id='{session['merchantId']}' AND whatsapp_phone='{outage_phone}';").strip() == "0"
    finally:
        compose("up", "-d", "--wait", "--wait-timeout", "180", "catalog-service", timeout=210)
    for _ in range(10):
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
