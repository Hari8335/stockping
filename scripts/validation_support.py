"""Dependency-free local validation helpers. Never print resolved configuration or tokens."""
import json
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def compose(*args, **kwargs):
    return subprocess.run(["docker", "compose", "--project-directory", str(ROOT), *args],
                          capture_output=True, text=True, check=True, timeout=kwargs.pop("timeout", 60), **kwargs)


def services():
    return json.loads(compose("config", "--format", "json").stdout)["services"]


def endpoint(config, service):
    return f"http://127.0.0.1:{config[service]['ports'][0]['published']}"


def request(base, method, path, body=None, token=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        payload = response.read()
        result = json.loads(payload) if payload else None
        allowed = expected if isinstance(expected, tuple) else (expected,)
        assert response.status in allowed, f"HTTP {response.status}, expected {expected}; code={result.get('code') if isinstance(result, dict) else None}"
        return result


def login(config):
    demo = config["demand-service"]["environment"]
    return request(endpoint(config, "demand-service"), "POST", "/api/auth/login", {
        "email": demo["DEMO_ADMIN_EMAIL"], "password": demo["DEMO_ADMIN_PASSWORD"],
    })
