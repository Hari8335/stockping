#!/usr/bin/env python3
"""Verify a running Compose skeleton without third-party Python dependencies."""
import json
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def compose(*args, check=True):
    return subprocess.run(
        ["docker", "compose", "--project-directory", str(ROOT), *args],
        check=check, text=True, capture_output=True, timeout=30,
    )

def check_application_http(services):
    for service, path in {
        "frontend": "/", "catalog-service": "/actuator/health",
        "demand-service": "/actuator/health", "messaging-service": "/health",
    }.items():
        port = services[service]["ports"][0]["published"]
        with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=10) as response:
            body = response.read().decode()
            assert response.status == 200, service
            if service == "frontend":
                assert "<title>StockPing</title>" in body, body
                # The dev HTML must serve the React entry point as well.
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/src/App.tsx", timeout=10) as app:
                    assert "The frontend is running" in app.read().decode()
            else:
                assert json.loads(body)["status"] == "UP", body
        print(f"PASS {service} HTTP health")


def check_database_isolation(services):
    # Check actual TCP authentication and deny every other service role.
    for owner in ("catalog", "demand", "messaging"):
        password = services["postgres"]["environment"][f"{owner.upper()}_DB_PASSWORD"]
        for target in ("catalog", "demand", "messaging"):
            result = compose(
                "exec", "-T", "-e", f"PGPASSWORD={password}", "postgres",
                "psql", "-h", "127.0.0.1", "-U", f"{owner}_user", "-d", f"{target}_db",
                "-Atc", "SELECT current_user || ':' || current_database()", check=False,
            )
            if target == owner:
                assert result.returncode == 0, "owned database connection failed"
                assert result.stdout.strip() == f"{owner}_user:{owner}_db"
            else:
                assert result.returncode != 0 and "permission denied for database" in result.stderr, "database isolation failed"
        print(f"PASS {owner} database ownership/isolation")


def check_broker(services):
    broker = services["rabbitmq"]["environment"]
    port = services["rabbitmq"]["ports"][1]["published"]
    password_mgr = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    url = f"http://127.0.0.1:{port}/api/overview"
    password_mgr.add_password(None, url, broker["RABBITMQ_DEFAULT_USER"], broker["RABBITMQ_DEFAULT_PASS"])
    opener = urllib.request.build_opener(urllib.request.HTTPBasicAuthHandler(password_mgr))
    with opener.open(url, timeout=10) as response:
        assert json.load(response)["rabbitmq_version"]
    print("PASS RabbitMQ authenticated management API")


def main():
    # Custom --env-file users can export COMPOSE_ENV_FILES.
    services = json.loads(compose("config", "--format", "json").stdout)["services"]
    check_application_http(services)
    check_database_isolation(services)
    check_broker(services)


if __name__ == "__main__":
    main()

