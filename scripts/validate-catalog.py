#!/usr/bin/env python3
"""Catalog acceptance: REST product/variant/inventory plus a generated Go gRPC client.

Runs in a disposable IsolatedStack (synthetic credentials and tenant, private network, disposable
volumes). Nothing is left behind and the developer databases are never touched.
"""
import urllib.parse
from validation_support import IsolatedStack, request


def main():
    with IsolatedStack("catalog", services=("catalog-service", "demand-service")) as stack:
        session = stack.login()
        merchant, token = session["merchantId"], session["accessToken"]
        assert merchant == stack.merchant_id
        base = stack.url("catalog-service")

        def call(method, path, body=None, expected=200):
            return request(base, method, path, body, token, expected)

        product = call("POST", "/api/products", {"name": "Galaxy S25 Ultra", "brand": "Samsung", "category": "Phones"}, 201)
        sku = "VALIDATE-" + stack.run_id
        variant = call("POST", f"/api/products/{product['id']}/variants", {
            "sku": sku, "attributes": {"storage": "512GB", "color": "Black"}, "price": 100.50,
        }, 201)
        path = f"/api/variants/{variant['id']}/inventory"
        inventory = call("GET", path)
        assert inventory["quantity"] == 0 and inventory["availabilityStatus"] == "UNAVAILABLE"
        updated = call("PATCH", path, {"quantity": 5})
        assert updated["changed"] and updated["restockTransition"] and updated["stockEventId"]
        assert updated["inventory"]["quantity"] == 5
        unchanged = call("PATCH", path, {"quantity": 5})
        assert not unchanged["changed"] and not unchanged["restockTransition"] and unchanged["stockEventId"] is None
        call("PATCH", path, {"quantity": -1}, 400)
        matches = call("GET", "/api/variants/search?" + urllib.parse.urlencode({"sku": sku, "storage": "512GB", "color": "black"}))
        assert matches["resolutionStatus"] == "MATCHED" and matches["candidates"][0]["id"] == variant["id"]
        print(f"PASS Catalog REST: product={product['id']} variant={variant['id']} SKU={sku} 0->5 restock; no-op and negative rejection", flush=True)

        binary = stack.go_build("catalog-grpc-check")
        print(stack.go_run(binary, "-target", "catalog-service:9091", "-merchant", merchant, "-sku", sku, "-quantity", "5",
                           env={"STOCKPING_TOKEN": token}), flush=True)


if __name__ == "__main__":
    main()
