#!/usr/bin/env python3
"""Credential-free fake provider used only by validate-messaging.py, never a Compose service."""
import json
import threading
import uuid

run_id = uuid.uuid4().hex
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state = {"mode": "success", "requests": []}
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
            assert body["messaging_product"] == "whatsapp" and body["type"] == "text"
            assert body["biz_opaque_callback_data"] == self.headers.get("X-Request-ID")
            # Synthetic test traffic only. No actual Meta credentials or real customer data.
            identifier = "synthetic.out." + run_id + "." + str(len(state["requests"]) + 1)
            state["requests"].append({"id": identifier, "body": body})
            if state["mode"] == "failure":
                self.respond(503, {"error": {"code": 2}})
            else:
                self.respond(200, {"messages": [{"id": identifier}]})

ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
