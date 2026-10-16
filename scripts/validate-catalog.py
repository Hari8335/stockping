#!/usr/bin/env python3
"""Create a unique local demo product and exercise REST plus a generated Go gRPC client.

Requires the running Compose stack and scripts/generate-proto.sh. Leaves the
created merchant-owned product/variant available for manual inspection.
"""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main():
    config = subprocess.run(
        ["docker", "compose", "--project-directory", str(ROOT), "config", "--format", "json"],
        capture_output=True, text=True, check=True, timeout=30,
    )
    services = json.loads(config.stdout)["services"]
    catalog = services["catalog-service"]
    http_port = catalog["ports"][0]["published"]
    grpc_port = catalog["ports"][1]["published"]
    merchant = catalog["environment"]["CATALOG_DEMO_MERCHANT_ID"]
    base = f"http://127.0.0.1:{http_port}"

    def request(method, path, body=None, expected=200):
        req = urllib.request.Request(
            base + path, data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json"}, method=method,
        )
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.load(response)
            assert response.status == expected, (response.status, result)
            return result

    product = request("POST", "/api/products", {
        "name": "Galaxy S25 Ultra", "brand": "Samsung", "category": "Phones",
    }, 201)
    sku = "VALIDATE-" + uuid.uuid4().hex[:12]
    variant = request("POST", f"/api/products/{product['id']}/variants", {
        "sku": sku, "attributes": {"storage": "512GB", "color": "Black"}, "price": 100.50,
    }, 201)
    path = f"/api/variants/{variant['id']}/inventory"
    inventory = request("GET", path)
    assert inventory["quantity"] == 0 and inventory["availabilityStatus"] == "UNAVAILABLE"
    updated = request("PATCH", path, {"quantity": 5})
    assert updated["changed"] and updated["restockTransition"] and updated["stockEventId"]
    assert updated["inventory"]["quantity"] == 5
    unchanged = request("PATCH", path, {"quantity": 5})
    assert not unchanged["changed"] and not unchanged["restockTransition"] and unchanged["stockEventId"] is None
    request("PATCH", path, {"quantity": -1}, 400)
    matches = request("GET", "/api/variants/search?" + urllib.parse.urlencode({"sku": sku, "storage": "512GB", "color": "black"}))
    assert matches["resolutionStatus"] == "MATCHED" and matches["candidates"][0]["id"] == variant["id"]
    print(f"PASS Catalog REST: product={product['id']} variant={variant['id']} SKU={sku} 0->5 restock; no-op and negative rejection", flush=True)
    linux = sys.platform.startswith("linux")
    target = os.environ.get("CATALOG_GRPC_VALIDATION_TARGET", f"{'127.0.0.1' if linux else 'host.docker.internal'}:{grpc_port}")
    network = ["--network", "host"] if linux else []
    subprocess.run([
        "docker", "run", "--rm", *network, "--user", f"{os.getuid()}:{os.getgid()}",
        "-e", "GOCACHE=/tmp/go-build", "-e", "GOPATH=/tmp/go",
        "-v", f"{ROOT}:/workspace:ro", "-w", "/workspace/messaging-service", "stockping-proto-tools",
        "go", "run", "/workspace/scripts/catalog-grpc-check.go", "-target", target,
        "-merchant", merchant, "-sku", sku, "-quantity", "5",
    ], check=True, timeout=600)


if __name__ == "__main__":
    main()
