#!/usr/bin/env python3
"""Create/extend private local configuration without printing credentials or replacing existing values."""
import os
from pathlib import Path
import secrets

root = Path(__file__).resolve().parent.parent
target = root / ".env"
existing = target.read_text() if target.exists() else ""
values = {line.split("=", 1)[0]: line.split("=", 1)[1] for line in existing.splitlines() if "=" in line and not line.startswith("#")}
for line in (root / ".env.example").read_text().splitlines():
    if "=" not in line or line.startswith("#"):
        continue
    key, default = line.split("=", 1)
    if not values.get(key):
        value = secrets.token_urlsafe(48) if key in {"AUTH_JWT_SECRET", "MESSAGING_DEMAND_TOKEN", "CATALOG_SERVICE_TOKEN"} else secrets.token_urlsafe(18) if key == "DEMO_ADMIN_PASSWORD" else default
        if key in values:
            existing = "\n".join(f"{key}={value}" if item.startswith(key + "=") else item for item in existing.splitlines()) + "\n"
        else:
            existing += f"\n{key}={value}\n"
        values[key] = value
descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(descriptor, "w") as output:
    output.write(existing)
target.chmod(0o600)
print("Local .env ready (mode 600). Demo login credentials are in that ignored file.")
