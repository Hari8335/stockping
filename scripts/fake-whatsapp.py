#!/usr/bin/env python3
"""Credential-free fake WhatsApp provider for isolated validators, never a Compose service.

Accepts only controlled synthetic recipients (FAKE_PROVIDER_RECIPIENT_PREFIX, default 1999). Any other
recipient is rejected with HTTP 400 and recorded in state["violations"]; the validation harness fails the run.
"""
import json
import os
import threading
import uuid

run_id = uuid.uuid4().hex
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RECIPIENT_PREFIX = os.environ.get("FAKE_PROVIDER_RECIPIENT_PREFIX", "1999")
state = {"mode": "success", "requests": [], "violations": []}
lock = threading.Lock()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def respond(self, code, payload):
        data = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        with lock:
            self.respond(200, state)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        with lock:
            if self.path == "/control":
                state["mode"] = body["mode"]
                self.respond(200, {"ok": True})
                return
            assert self.path == "/v26.0/200/messages"
            assert self.headers.get("Authorization") == "Bearer synthetic-access-token"
            assert self.headers.get_content_type() == "application/json"
            assert body["messaging_product"] == "whatsapp" and body["type"] in ("text", "template")
            assert body["biz_opaque_callback_data"] == self.headers.get("X-Request-ID")
            recipient = str(body.get("to", ""))
            if not (recipient.startswith(RECIPIENT_PREFIX) and recipient.isdigit() and len(recipient) == len(RECIPIENT_PREFIX) + 7):
                state["violations"].append({"to_length": len(recipient)})  # Never store the rejected number itself.
                self.respond(400, {"error": {"code": 131030, "message": "non-synthetic recipient"}})
                return
            # Synthetic test traffic only. No actual Meta credentials or real customer data.
            identifier = "synthetic.out." + run_id + "." + str(len(state["requests"]) + 1)
            state["requests"].append({"id": identifier, "body": body})
            if state["mode"] == "rejected":
                self.respond(400, {"error": {"code": 100}})
            elif state["mode"] == "failure":
                self.respond(503, {"error": {"code": 2}})
            else:
                self.respond(200, {"messages": [{"id": identifier}]})

ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
