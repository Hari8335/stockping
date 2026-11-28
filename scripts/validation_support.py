"""Shared, dependency-free validation infrastructure. Never print resolved configuration or tokens.

Every validator runs inside an IsolatedStack: a uniquely named, disposable Compose project with
  * synthetic per-run credentials written to a private --env-file (the root .env is never read,
    and the subprocess environment is scrubbed so exported shell variables cannot leak in);
  * images built from the working tree under stockping-validation-* tags (developer images untouched);
  * a fake WhatsApp provider on the private network, real sending forced off and an empty allowlist;
  * controlled synthetic phone numbers (+1999…, an unassigned NANP area code) that the fake
    provider enforces;
  * teardown that removes only this project's containers and volumes.
The approach generalises scripts/validate-stage4.py.
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROJECT_PREFIX = "stockping-validation-"
VALIDATION_PROJECT = re.compile(r"stockping-validation-[a-z0-9]+-[0-9a-f]{12}")
SYNTHETIC_PHONE_PREFIX = "+1999"  # NANP area code 999 is reserved/unassigned: never a real subscriber.
APP_SERVICES = ("catalog-service", "demand-service", "messaging-service", "frontend")
PORTS = {"frontend": ("FRONTEND_PORT",), "catalog-service": ("CATALOG_PORT", "CATALOG_GRPC_PORT"),
         "demand-service": ("DEMAND_PORT",), "messaging-service": ("MESSAGING_PORT",),
         "postgres": ("POSTGRES_PORT",), "rabbitmq": ("RABBITMQ_PORT", "RABBITMQ_MANAGEMENT_PORT")}
# Only what Docker itself needs. Everything else (including any exported StockPing/WhatsApp values) is dropped.
PASSTHROUGH_ENV = ("PATH", "HOME", "USER", "TMPDIR", "LANG", "LC_ALL", "DOCKER_HOST", "DOCKER_CONTEXT",
                   "DOCKER_CONFIG", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY")
PROVIDER_IMAGE = "python:3.13-alpine"
GO_TOOLS_IMAGE = "stockping-proto-tools"


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


def poll(description, predicate, timeout=70, interval=0.3):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except (urllib.error.URLError, TimeoutError, ConnectionError, AssertionError):
            pass
        time.sleep(interval)
    raise AssertionError("Timed out: " + description)


def scrubbed_env(**extra):
    env = {key: os.environ[key] for key in PASSTHROUGH_ENV if key in os.environ}
    env.update(extra)
    return env


def docker(*args, timeout=120, env=None, **kwargs):
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=True, timeout=timeout,
                          env=env or scrubbed_env(), **kwargs)


class SyntheticPhones:
    """Deterministic per-run synthetic recipients: +19990000001, +19990000002, ..."""

    def __init__(self, start=1):
        self._next = start

    def next(self):
        phone = f"{SYNTHETIC_PHONE_PREFIX}{self._next:07d}"
        self._next += 1
        return phone


def is_synthetic_phone(phone):
    return bool(re.fullmatch(r"\+?1999\d{7}", phone or ""))


class IsolatedStack:
    """Context manager owning one disposable Compose project. See the module docstring."""

    def __init__(self, label, services, stock_notifications=False, stock_template=None):
        """stock_template: optional synthetic (name, language, parameters) for fake-provider template sends."""
        assert re.fullmatch(r"[a-z0-9]+", label), "label must be lowercase alphanumeric"
        unknown = set(services) - set(APP_SERVICES)
        assert not unknown, f"unknown services {unknown}"
        self.services = tuple(s for s in APP_SERVICES if s in services)
        self.run_id = uuid.uuid4().hex[:12]
        self.project = f"{PROJECT_PREFIX}{label}-{self.run_id}"
        self.provider_name = self.project + "-provider"
        self.merchant_id = str(uuid.uuid4())
        self.admin_email = f"admin-{self.run_id}@validation.stockping.invalid"
        self.admin_password = secrets.token_urlsafe(24)
        self.app_secret = "synthetic-app-secret-" + secrets.token_hex(8)
        self.access_token = "synthetic-access-token"  # Asserted by scripts/fake-whatsapp.py.
        self.verify_token = "synthetic-verify-token-" + secrets.token_hex(8)
        self.phones = SyntheticPhones()
        self.values = {
            "POSTGRES_USER": "validation_admin", "POSTGRES_PASSWORD": secrets.token_hex(24),
            "CATALOG_DB_PASSWORD": secrets.token_hex(24), "DEMAND_DB_PASSWORD": secrets.token_hex(24),
            "MESSAGING_DB_PASSWORD": secrets.token_hex(24),
            "RABBITMQ_USER": "validation", "RABBITMQ_PASSWORD": secrets.token_hex(24),
            "AUTH_JWT_SECRET": secrets.token_hex(32), "MESSAGING_DEMAND_TOKEN": secrets.token_hex(32),
            "CATALOG_SERVICE_TOKEN": secrets.token_hex(32),
            "DEMAND_PROFILES": "demo", "DEMO_MERCHANT_ID": self.merchant_id,
            "DEMO_ADMIN_EMAIL": self.admin_email, "DEMO_ADMIN_PASSWORD": self.admin_password,
            "STOCK_EVENTS_ENABLED": "true",
            "STOCK_NOTIFICATIONS_ENABLED": "true" if stock_notifications else "false",
            "WHATSAPP_ENABLED": "true", "WHATSAPP_VERIFY_TOKEN": self.verify_token,
            "WHATSAPP_APP_SECRET": self.app_secret, "WHATSAPP_ACCESS_TOKEN": self.access_token,
            "WHATSAPP_PHONE_NUMBER_ID": "200", "WHATSAPP_WABA_ID": "100", "WHATSAPP_GRAPH_API_VERSION": "v26.0",
            "WHATSAPP_MERCHANT_ID": self.merchant_id, "WHATSAPP_API_BASE_URL": f"http://{self.provider_name}:8080",
            "WHATSAPP_ALLOW_TEST_ENDPOINT": "true",
            **dict(zip(("WHATSAPP_STOCK_TEMPLATE_NAME", "WHATSAPP_STOCK_TEMPLATE_LANGUAGE", "WHATSAPP_STOCK_TEMPLATE_PARAMETERS"),
                       stock_template or ("", "", ""))),
            "WHATSAPP_STOCK_REAL_SEND_ENABLED": "false", "WHATSAPP_STOCK_ALLOWED_RECIPIENTS": "",
            "STOCKPING_AI_ENABLED": "true", "STOCKPING_AI_PROVIDER": "mock", "STOCKPING_AI_API_KEY": "",
            "STOCKPING_AI_API_URL": "http://127.0.0.1:9/disabled",
        }
        self._sockets = []
        for keys in PORTS.values():
            for key in keys:
                sock = socket.socket()
                sock.bind(("127.0.0.1", 0))
                self._sockets.append(sock)
                self.values[key] = str(sock.getsockname()[1])
        self._tmp = tempfile.TemporaryDirectory(prefix=self.project + "-")
        self.tmp = Path(self._tmp.name)
        self.env_file, self.override_file = self.tmp / "validation.env", self.tmp / "compose.override.json"
        self.override = {"services": {s: {"image": f"stockping-validation-{s}:local"} for s in APP_SERVICES}}
        self.override["services"]["messaging-service"]["environment"] = {}
        self._write_config()
        self.network = None
        self.provider = None

    # ---------- configuration ----------
    def _write_config(self):
        self.env_file.write_text("".join(f"{k}={v}\n" for k, v in self.values.items()))
        self.env_file.chmod(0o600)
        self.override_file.write_text(json.dumps(self.override))
        self.override_file.chmod(0o600)

    def compose(self, *args, timeout=120, **kwargs):
        assert is_validation_project(self.project), "refusing to operate on a non-validation project"
        return docker("compose", "--project-directory", str(ROOT), "--project-name", self.project,
                      "--env-file", str(self.env_file), "-f", str(ROOT / "docker-compose.yml"),
                      "-f", str(self.override_file), *args, timeout=timeout, **kwargs)

    def _assert_safe_configuration(self):
        resolved = json.loads(self.compose("config", "--format", "json").stdout)
        assert resolved["name"] == self.project
        messaging = resolved["services"]["messaging-service"]["environment"]
        assert messaging["WHATSAPP_API_BASE_URL"] == f"http://{self.provider_name}:8080", "provider must be the fake"
        assert messaging["WHATSAPP_STOCK_REAL_SEND_ENABLED"] == "false" and messaging["WHATSAPP_STOCK_ALLOWED_RECIPIENTS"] == ""
        assert messaging["WHATSAPP_ACCESS_TOKEN"] == self.access_token and messaging["WHATSAPP_APP_SECRET"] == self.app_secret
        assert messaging["WHATSAPP_STOCK_TEMPLATE_NAME"] in ("", "synthetic_stock_test"), "only the synthetic template is allowed"
        demand = resolved["services"]["demand-service"]["environment"]
        assert demand["DEMO_ADMIN_EMAIL"] == self.admin_email and demand["STOCKPING_AI_PROVIDER"] == "mock"
        assert demand["STOCKPING_AI_API_KEY"] == ""
        for name, service in resolved["services"].items():
            if name in APP_SERVICES:
                assert service["image"] == f"stockping-validation-{name}:local", name
        for volume in resolved.get("volumes", {}).values():
            assert volume["name"].startswith(self.project + "_"), "volumes must belong to the isolated project"

    # ---------- lifecycle ----------
    def __enter__(self):
        try:
            self._assert_safe_configuration()
            if os.environ.get("STOCKPING_VALIDATION_SKIP_BUILD") != "1":
                print(f"BUILD validation images for {', '.join(self.services)} (cached layers reused)", flush=True)
                self.compose("build", *self.services, timeout=1800)
            for sock in self._sockets:
                sock.close()
            self.compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "240",
                         "postgres", "rabbitmq", *self.services, timeout=300)
            postgres = self.compose("ps", "-q", "postgres").stdout.strip()
            self.network = next(iter(json.loads(docker("inspect", postgres).stdout)[0]["NetworkSettings"]["Networks"]))
            if {"catalog-service", "demand-service"} <= set(self.services):
                self.await_catalog_rpc()
            if "messaging-service" in self.services:
                self._start_provider()
            print(f"READY isolated project {self.project} (synthetic credentials, fake provider, disposable volumes)", flush=True)
            return self
        except BaseException:
            self._diagnostics()
            self.teardown()
            raise

    def __exit__(self, exc_type, exc, tb):
        if exc_type is not None:
            self._diagnostics()
        if os.environ.get("STOCKPING_VALIDATION_KEEP") == "1":
            print(f"KEPT isolated project {self.project}; remove with: python3 scripts/validation_support.py --cleanup", flush=True)
            return False
        self.teardown(check_recipients=exc_type is None)
        return False

    def await_catalog_rpc(self):
        """HTTP health does not prove the authenticated Demand -> Catalog gRPC path is warm (JIT/connection
        warm-up can exceed the first RPC deadline). Probe it with the side-effect-free AI preview endpoint."""
        token = self.login()["accessToken"]
        poll("authenticated Demand -> Catalog RPC readiness",
             lambda: request(self.url("demand-service"), "POST", "/api/ai/query-preview",
                             {"query": "S25 Ultra 512 black available?"}, token, 200), timeout=90, interval=1)

    def _diagnostics(self):
        try:
            path = Path(tempfile.gettempdir()) / (self.project + "-diagnostics.log")
            path.write_text(self.compose("logs", "--no-color", "--tail", "150").stdout)
            path.chmod(0o600)
            print("Failure diagnostics (synthetic data only): " + str(path), file=sys.stderr, flush=True)
        except Exception:
            pass

    def teardown(self, check_recipients=False):
        violations = []
        try:
            if check_recipients and self.provider:
                violations = request(self.provider, "GET", "/").get("violations", [])
        except Exception:
            pass
        try:
            docker("rm", "-f", self.provider_name)
        except subprocess.CalledProcessError:
            pass
        volumes = docker("volume", "ls", "-q", "--filter", f"label=com.docker.compose.project={self.project}").stdout.split()
        assert all(v.startswith(self.project + "_") for v in volumes), "refusing to remove volumes outside the project"
        self.compose("down", "--volumes", "--remove-orphans", timeout=180)
        left = docker("volume", "ls", "-q", "--filter", f"label=com.docker.compose.project={self.project}").stdout.split()
        self._tmp.cleanup()
        print(f"RESTORED removed isolated project {self.project}: containers and {len(volumes) - len(left)} disposable volume(s)", flush=True)
        assert not left, f"disposable volumes remain: {left}"
        assert not violations, f"fake provider rejected {len(violations)} non-synthetic recipient(s)"

    # ---------- services ----------
    def url(self, service, index=0):
        return "http://127.0.0.1:" + self.values[PORTS[service][index]]

    def stop(self, service):
        self.compose("stop", service)

    def start(self, service, timeout=180):
        self.compose("up", "-d", "--no-deps", "--no-build", "--wait", "--wait-timeout", str(timeout), service, timeout=timeout + 30)

    def reconfigure_messaging(self, **settings):
        self.override["services"]["messaging-service"]["environment"].update(settings)
        self._write_config()
        self._assert_safe_configuration()
        self.start("messaging-service", 60)

    def logs(self, service, since="5m"):
        return self.compose("logs", "--no-color", "--since", since, service).stdout

    def sql(self, owner, statement):
        assert owner in ("catalog", "demand", "messaging")
        return self.compose("exec", "-T", "postgres", "sh", "-c",
                            f'PGPASSWORD="${owner.upper()}_DB_PASSWORD" psql -h 127.0.0.1 -U {owner}_user -d {owner}_db -v ON_ERROR_STOP=1 -At',
                            input=statement).stdout.strip()

    def lock_process(self, owner):
        """psql session for timing fixtures (e.g. holding a bounded lock); caller writes SQL to stdin."""
        return subprocess.Popen(["docker", "compose", "--project-directory", str(ROOT), "--project-name", self.project,
                                 "--env-file", str(self.env_file), "-f", str(ROOT / "docker-compose.yml"), "-f", str(self.override_file),
                                 "exec", "-T", "postgres", "sh", "-c",
                                 f'PGPASSWORD="${owner.upper()}_DB_PASSWORD" psql -h 127.0.0.1 -U {owner}_user -d {owner}_db -v ON_ERROR_STOP=1 -qAt'],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=scrubbed_env())

    # ---------- identities ----------
    def login(self, email=None, password=None):
        return request(self.url("demand-service"), "POST", "/api/auth/login",
                       {"email": email or self.admin_email, "password": password or self.admin_password})

    def create_tenant(self, name="Isolated validation tenant"):
        """Second merchant + admin with its OWN random password (never a copied hash)."""
        merchant, user = str(uuid.uuid4()), str(uuid.uuid4())
        email = f"tenant-{uuid.uuid4().hex[:12]}@validation.stockping.invalid"
        password = secrets.token_urlsafe(24)
        # Hash inside the disposable server's maintenance database, so no service schema is altered.
        hashed = self.compose("exec", "-T", "postgres", "sh", "-c",
                              'psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -qAt',
                              input=f"CREATE EXTENSION IF NOT EXISTS pgcrypto;\nSELECT crypt('{password}', gen_salt('bf', 12));\n").stdout.strip()
        assert hashed.startswith("$2") and len(hashed) == 60
        self.sql("demand", f"""BEGIN;
            INSERT INTO merchants(id,name,timezone,status,created_at,updated_at) VALUES ('{merchant}','{name}','UTC','ACTIVE',now(),now());
            INSERT INTO users(id,merchant_id,email,password_hash,role,status,created_at,updated_at)
              VALUES ('{user}','{merchant}','{email}','{hashed}','MERCHANT_ADMIN','ACTIVE',now(),now());
            COMMIT;""")
        session = self.login(email, password)
        return {"merchantId": merchant, "userId": user, "email": email, "password": password, "token": session["accessToken"]}

    # ---------- fake WhatsApp provider ----------
    def _start_provider(self):
        docker("run", "-d", "--name", self.provider_name, "--network", self.network, "-p", "127.0.0.1::8080",
               "--label", f"com.docker.compose.project={self.project}",
               "-e", "FAKE_PROVIDER_RECIPIENT_PREFIX=" + SYNTHETIC_PHONE_PREFIX.lstrip("+"),
               "-v", f"{ROOT / 'scripts/fake-whatsapp.py'}:/server.py:ro", PROVIDER_IMAGE, "python", "/server.py")
        port = json.loads(docker("inspect", self.provider_name).stdout)[0]["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
        self.provider = "http://127.0.0.1:" + port
        poll("fake provider ready", lambda: request(self.provider, "GET", "/"), timeout=30)

    def provider_requests(self):
        return request(self.provider, "GET", "/")["requests"]

    def provider_mode(self, mode):
        request(self.provider, "POST", "/control", {"mode": mode})

    # ---------- signed webhooks ----------
    def signed(self, payload, valid=True):
        data = json.dumps(payload).encode()
        signature = hmac.new(self.app_secret.encode(), data, hashlib.sha256).hexdigest() if valid else "0" * 64
        req = urllib.request.Request(self.url("messaging-service") + "/webhooks/whatsapp", data=data, method="POST",
                                     headers={"Content-Type": "application/json", "X-Hub-Signature-256": "sha256=" + signature})
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers.get("X-Request-ID")

    @staticmethod
    def envelope(value):
        return {"object": "whatsapp_business_account", "entry": [{"id": "100", "changes": [
            {"field": "messages", "value": {"messaging_product": "whatsapp", "metadata": {"phone_number_id": "200"}, **value}}]}]}

    def inbound(self, phone, text, identifier=None):
        assert is_synthetic_phone(phone), "validators may only use synthetic phones"
        identifier = identifier or "synthetic.in." + uuid.uuid4().hex
        return identifier, self.envelope({"messages": [{"id": identifier, "from": phone[1:], "timestamp": str(int(time.time())),
                                                        "type": "text", "text": {"body": text}}]})

    # ---------- generated Go gRPC clients ----------
    def go_build(self, script):
        """Compile scripts/<script>.go once with the proto-tools image; returns the binary path inside /checks."""
        cache = Path(tempfile.gettempdir()) / "stockping-go-cache"
        for sub in ("build", "pkg"):
            (cache / sub).mkdir(parents=True, exist_ok=True)
        docker("run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}", "-e", "CGO_ENABLED=0",
               "-e", "GOCACHE=/tmp/go-build", "-e", "GOPATH=/tmp/go",
               "-v", f"{cache / 'build'}:/tmp/go-build", "-v", f"{cache / 'pkg'}:/tmp/go/pkg",
               "-v", f"{ROOT}:/workspace:ro", "-v", f"{self.tmp}:/checks", "-w", "/workspace/messaging-service",
               GO_TOOLS_IMAGE, "go", "build", "-trimpath", "-o", f"/checks/{script}", f"/workspace/scripts/{script}.go", timeout=600)
        return f"/checks/{script}"

    def go_run(self, binary, *args, env=None):
        """Run a compiled check on the isolated Compose network; secrets are passed by name, not argv."""
        env = env or {}
        flags = [x for key in env for x in ("-e", key)]
        result = docker("run", "--rm", "--network", self.network, "-v", f"{self.tmp}:/checks:ro", *flags,
                        "alpine:3.23", binary, *args, env=scrubbed_env(**env), timeout=120)
        return result.stdout.strip()


def is_validation_project(name):
    """Only names generated by IsolatedStack qualify (prefix + label + 12-hex run id). Developer and
    unrelated projects, including a hand-named "stockping-validation-foo", never match."""
    return bool(VALIDATION_PROJECT.fullmatch(name or "")) and name != "stockping"


def cleanup_stale(include_running=False):
    """Remove leftover validation projects (e.g. after an interrupted run). Never touches other projects.

    Candidates are discovered by Compose project label, then each resource is re-checked: containers and
    networks by exact project label, volumes by exact label AND the "<project>_" name prefix. Projects with
    running containers (possibly a validation in progress elsewhere) are skipped unless include_running.
    """
    labels = set()
    for kind in (("ps", "-a"), ("volume", "ls"), ("network", "ls")):
        labels.update(docker(*kind, "--format", '{{.Label "com.docker.compose.project"}}').stdout.split())
    names = sorted(name for name in labels if is_validation_project(name))
    for name in names:
        label = f"label=com.docker.compose.project={name}"
        running = docker("ps", "-q", "--filter", label).stdout.split()
        if running and not include_running:
            print(f"SKIPPED {name}: {len(running)} running container(s); rerun with --include-running if it is stale")
            continue
        removed = 0
        for container in docker("ps", "-aq", "--filter", label).stdout.split():
            docker("rm", "-f", container); removed += 1
        for volume in docker("volume", "ls", "-q", "--filter", label).stdout.split():
            if volume.startswith(name + "_"):
                docker("volume", "rm", volume); removed += 1
            else:
                print(f"KEPT volume {volume}: labelled {name} but not named for it")
        for network in docker("network", "ls", "--filter", label, "--format", "{{.ID}} {{.Name}}").stdout.splitlines():
            network_id, network_name = network.split(" ", 1)
            if network_name.startswith(name + "_"):
                docker("network", "rm", network_id); removed += 1
        print(f"REMOVED stale validation project {name}: {removed} resource(s)" if removed else f"NOTHING REMOVED for {name}")
    if not names:
        print("No stale validation projects")


if __name__ == "__main__":
    if sys.argv[1:2] == ["--cleanup"] and set(sys.argv[2:]) <= {"--include-running"}:
        cleanup_stale(include_running="--include-running" in sys.argv[2:])
    else:
        print("usage: python3 scripts/validation_support.py --cleanup [--include-running]", file=sys.stderr)
        sys.exit(2)
