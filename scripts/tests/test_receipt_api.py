"""Synthetic loopback tests; no remote hosts, private receipts or real credentials."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
import tempfile
import threading
import time
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "receipt_api.py"
SPEC = importlib.util.spec_from_file_location("receipt_api", SCRIPT)
client = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(client)
TOKEN = "synthetic-ingest-token-for-tests-only-000001"
DRAFT_ID = str(uuid.uuid4())


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, value, code=200, **headers):
        body = json.dumps(value).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for key, header in headers.items():
            self.send_header(key.replace("_", "-"), header)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        self.server.requests.append((self.command, self.path, dict(self.headers), b""))
        if self.server.redirect:
            self.reply({"token": TOKEN}, 307, Location=self.server.redirect)
        elif self.server.error:
            self.reply({"detail": TOKEN}, 401)
        elif self.path.endswith("/health"):
            self.reply({"status": "ok", "echo": TOKEN})
        else:
            self.reply({"drafts": [], "id": DRAFT_ID})

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        self.server.requests.append((self.command, self.path, dict(self.headers), body))
        if self.server.error:
            self.reply({"detail": TOKEN}, 503)
            return
        if self.server.redirect:
            self.reply({"token": TOKEN}, 307, Location=self.server.redirect)
            return
        if self.path.endswith("/v1/evidence"):
            self.reply(
                {
                    "sha256": hashlib.sha256(body).hexdigest(),
                    "size_bytes": len(body),
                    "page_count": 1,
                }
            )
            return
        key = self.headers["Idempotency-Key"]
        # A commit followed by a delayed response is the important timeout boundary.
        self.server.keys.setdefault(key, DRAFT_ID)
        time.sleep(self.server.delay)
        self.reply({"id": self.server.keys[key], "status": "needs_review"}, 201)


class ReceiptClientTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.private = Path(self.temporary.name)
        self.private.chmod(0o700)
        self.state = self.private / "transaction.json"
        self.body = self.private / "draft.json"
        self.body.write_text(
            '{"candidate":{"printed_total_yuan":0},"evidence":[],"source":{"kind":"ai_candidate"}}'
        )
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.requests, self.server.keys = [], {}
        self.server.delay, self.server.redirect, self.server.error = 0, "", False
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.url = f"http://127.0.0.1:{self.server.server_port}/receipts"

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def call(self, *args, token=TOKEN):
        output, error = io.StringIO(), io.StringIO()
        with (
            patch.dict(
                os.environ,
                {
                    "PIXELRECEIPT_INGEST_TOKEN": token,
                    "PIXELRECEIPT_INGEST_TOKEN_FILE": "",
                },
                clear=False,
            ),
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(error),
        ):
            result = client.main(["--base-url", self.url, *map(str, args)])
        self.assertNotIn(TOKEN, output.getvalue() + error.getvalue())
        return result, output.getvalue(), error.getvalue()

    def create(self, *options):
        return self.call(
            *options, "create-draft", "--body", self.body, "--request-state", self.state
        )

    def test_health_prefix_redaction_and_auth_header(self):
        code, output, _ = self.call("health")
        self.assertEqual(code, 0)
        self.assertIn("[REDACTED]", output)
        _, path, headers, _ = self.server.requests[0]
        self.assertEqual(path, "/receipts/health")
        self.assertEqual(headers["Authorization"], "Bearer " + TOKEN)

    def test_all_commands_send_application_user_agent(self):
        photo = self.private / "synthetic.png"
        photo.write_bytes(b"synthetic-user-agent-test")
        commands = [
            ("health",),
            ("upload", "--file", photo),
            (
                "create-draft",
                "--body",
                self.body,
                "--request-state",
                self.state,
            ),
            ("get-draft", DRAFT_ID),
            ("list-drafts",),
        ]
        for command in commands:
            with self.subTest(command=command[0]):
                self.assertEqual(self.call(*command)[0], 0)
        self.assertEqual(len(self.server.requests), len(commands))
        for _, path, headers, _ in self.server.requests:
            with self.subTest(path=path):
                self.assertEqual(headers.get("User-Agent"), "PixelReceipt-Ingest/1.0")

    def test_raw_upload_hash_and_bytes(self):
        photo = self.private / "synthetic.png"
        photo.write_bytes(b"synthetic-byte-preservation-test")
        code, output, _ = self.call("upload", "--file", photo)
        self.assertEqual(code, 0)
        self.assertEqual(self.server.requests[0][3], photo.read_bytes())
        self.assertEqual(
            json.loads(output)["result"]["size_bytes"], photo.stat().st_size
        )

    def test_create_replay_and_restart_reuse_persisted_key(self):
        self.assertEqual(self.create()[0], 0)
        original = json.loads(self.state.read_text())
        self.assertEqual(self.create()[0], 0)
        final = json.loads(self.state.read_text())
        self.assertEqual(original["idempotency_key"], final["idempotency_key"])
        self.assertEqual(len(self.server.keys), 1)
        self.assertEqual(final["attempts"], 2)
        self.assertEqual(self.state.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(TOKEN, self.state.read_text())

    def test_committed_but_timeout_keeps_key_and_replay_recovers(self):
        self.server.delay = 0.2
        code, _, error = self.create("--timeout", "0.05")
        self.assertEqual(code, 4)
        self.assertIn("outcome_unknown", error)
        original = json.loads(self.state.read_text())
        self.assertEqual(original["status"], "outcome_unknown")
        self.server.delay = 0
        self.assertEqual(self.create()[0], 0)
        self.assertEqual(len(self.server.keys), 1)
        self.assertEqual(
            original["idempotency_key"],
            json.loads(self.state.read_text())["idempotency_key"],
        )

    def test_changed_content_or_endpoint_cannot_reuse_state(self):
        self.assertEqual(self.create()[0], 0)
        self.body.write_text('{"candidate":{"printed_total_yuan":1}}')
        code, _, error = self.create()
        self.assertEqual(code, 2)
        self.assertIn("state_mismatch", error)
        self.assertEqual(len(self.server.requests), 1)

    def test_mutation_server_error_keeps_unknown_outcome_and_same_key(self):
        self.server.error = True
        self.assertEqual(self.create()[0], 4)
        state = json.loads(self.state.read_text())
        self.assertEqual(state["status"], "outcome_unknown")
        self.server.error = False
        self.assertEqual(self.create()[0], 0)
        self.assertEqual(
            state["idempotency_key"],
            json.loads(self.state.read_text())["idempotency_key"],
        )

    def test_killed_in_flight_state_still_reuses_same_key(self):
        self.assertEqual(self.create()[0], 0)
        state = json.loads(self.state.read_text())
        state["status"] = "in_flight"
        self.state.write_text(json.dumps(state))
        self.assertEqual(self.create()[0], 0)
        self.assertEqual(len(self.server.keys), 1)

    def test_redirect_is_never_followed_and_body_is_not_echoed(self):
        self.server.redirect = self.url + "/stolen"
        code, _, error = self.call("health")
        self.assertEqual(code, 3)
        self.assertIn("redirect_refused", error)
        self.assertEqual(len(self.server.requests), 1)
        self.assertNotIn("stolen", error)

    def test_http_error_body_not_echoed(self):
        self.server.error = True
        code, _, error = self.call("health")
        self.assertEqual(code, 3)
        self.assertEqual(json.loads(error)["http_status"], 401)

    def test_token_file_permissions_and_no_implicit_reviewer_token(self):
        token_file = self.private / "ingest-token"
        token_file.write_text(TOKEN + "\n")
        token_file.chmod(0o600)
        self.assertEqual(
            self.call("--token-file", token_file, "health", token="")[0], 0
        )
        token_file.chmod(0o644)
        self.assertEqual(
            self.call("--token-file", token_file, "health", token="")[0], 2
        )
        with patch.dict(os.environ, {"PIXELRECEIPT_TOKEN": TOKEN}):
            self.assertEqual(self.call("health", token="")[0], 2)

    def test_symlink_or_public_state_refused(self):
        outside = self.private / "outside"
        outside.write_text("unchanged")
        self.state.symlink_to(outside)
        self.assertEqual(self.create()[0], 2)
        self.assertEqual(outside.read_text(), "unchanged")
        self.state.unlink()
        self.private.chmod(0o755)
        self.assertEqual(self.create()[0], 2)

    def test_untrusted_json_duplicate_and_float_are_not_sent(self):
        for body in ['{"amount":1e2}', '{"amount":1.0}', '{"a":1,"a":2}']:
            self.body.write_text(body)
            self.assertEqual(self.create()[0], 2)
        self.assertFalse(self.server.requests)

    def test_transport_policy(self):
        for value in [
            "http://192.168.1.2",
            "http://localhost",
            "https://user:secret@example.com",
            "https://example.com/receipts?token=secret",
            "https://example.com/a/../b",
            "https://example.com/%2e%2e",
            "https://example.com/#fragment",
        ]:
            with self.subTest(value=value), self.assertRaises(client.ClientError):
                client.base_url(value)
        self.assertEqual(
            client.base_url("https://example.com/receipts/"),
            "https://example.com/receipts",
        )
        self.assertEqual(client.base_url("http://[::1]:18991"), "http://[::1]:18991")


if __name__ == "__main__":
    unittest.main()
