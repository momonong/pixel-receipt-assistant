import io
import json
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch

from PIL import Image

from tools.receipt_lab.engines import Engines, validate_candidate
from tools.receipt_lab.server import Lab, Server
from tools.receipt_lab.store import LabError, Store, diagnostics


def image_bytes():
    out = io.BytesIO()
    Image.new("RGB", (80, 120), "white").save(out, "PNG")
    return out.getvalue()


def candidate():
    return {"merchant": "測試商店", "date": None, "currency": "TWD", "totalMinor": "100",
            "items": [{"name": "茶", "quantity": "3", "unitPriceMinor": None, "lineTotalMinor": "100"}],
            "adjustments": [], "warnings": []}


class FixtureEngines:
    model = "gemini-test"
    def capabilities(self):
        return {"mlkit": {"configured": True}, "gemini": {"configured": True}}
    def run_mlkit(self, run, case, store):
        store.image_bytes(case["images"][0])
        return {"status": "succeeded", "candidate": candidate(), "fixture": True}
    run_gemini = run_mlkit


class LabTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(self.temp.name)
        self.engines = FixtureEngines()
        self.lab = Lab(self.store, self.engines)
        self.image = self.store.upload(image_bytes(), "收據.png")
        self.case = self.store.create_case({"title": "收據", "images": [self.image["id"]]})
    def tearDown(self):
        self.lab.stop()
        if self.lab.worker:
            self.lab.worker.join(3)
        self.temp.cleanup()
    def submit(self, **values):
        return self.lab.submit(self.case["id"], {"requestId": "request-one", "engine": "mlkit", **values})
    def wait_run(self, identity):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            run = self.store.get("run", identity)
            if run["status"] not in {"queued", "running"}:
                return run
            time.sleep(.02)
        self.fail("worker did not finish")
    def test_uploaded_original_is_deduplicated_and_persists_with_notes(self):
        self.assertEqual(self.image["id"], self.store.upload(image_bytes(), "again.png")["id"])
        self.store.note(self.case["id"], "這是分析，不是真值")
        reopened = Store(self.temp.name)
        self.assertEqual(image_bytes(), reopened.image_bytes(self.image["id"]))
        self.assertEqual("analysis_not_ground_truth", reopened.get("case", self.case["id"])["notes"][0]["kind"])
    def test_bad_image_and_duplicates_and_unknown_file_are_rejected(self):
        for body in (b"not an image", image_bytes()[:40], b""):
            with self.assertRaises(LabError): self.store.upload(body, "fake.png")
        with self.assertRaises(LabError): self.store.create_case({"title": "x", "images": [self.image["id"]] * 2})
        with self.assertRaises(LabError): self.store.image_bytes("../../outside")
    def test_corrupt_stored_image_is_not_sent_to_engine(self):
        (self.store.root / "images" / self.image["id"]).write_bytes(b"changed")
        with self.assertRaisesRegex(LabError, "完整性"): self.store.image_bytes(self.image["id"])
    def test_request_id_prevents_duplicate_and_rejects_changed_scope(self):
        first = self.submit()
        self.assertEqual(first["id"], self.submit()["id"])
        with self.assertRaises(LabError): self.submit(consent={"changed": True})
        self.assertEqual(1, len(self.store.all("run")))
    def test_cloud_requires_exact_photos_model_and_purpose(self):
        correct = {"provider": "google-gemini", "model": self.engines.model,
                   "purpose": "receipt-extraction", "imageHashes": self.case["images"]}
        for consent in (None, {**correct, "imageHashes": []}, {**correct, "model": "other"}, {**correct, "purpose": "other"}):
            with self.assertRaises(LabError): self.submit(engine="gemini", consent=consent)
        run = self.submit(engine="gemini", consent=correct)
        self.assertEqual(correct, run["consent"])
    def test_worker_result_keeps_unknown_and_does_not_claim_accuracy(self):
        self.lab.start()
        result = self.wait_run(self.submit()["id"])
        self.assertEqual("succeeded", result["status"])
        self.assertEqual("not_evaluated", result["diagnostics"]["accuracy"])
        self.assertEqual(1, result["diagnostics"]["unknownFields"])
        self.assertEqual([], self.store.get("case", self.case["id"])["notes"])
    def test_cancellation_cannot_be_overwritten_by_late_output(self):
        started, release = threading.Event(), threading.Event()
        def delayed(*args):
            started.set(); release.wait(3)
            return {"status": "succeeded", "candidate": candidate()}
        self.engines.run_mlkit = delayed
        self.lab.start()
        run = self.submit()
        self.assertTrue(started.wait(3))
        self.lab.cancel(run["id"])
        with self.assertRaisesRegex(LabError, "結束處理"):
            self.lab.delete_case(self.case["id"])
        release.set();time.sleep(.1)
        saved = self.store.get("run", run["id"])
        self.assertEqual("cancelled", saved["status"])
        self.assertIsNone(saved["result"])
        self.lab.delete_case(self.case["id"])
        self.assertTrue(self.lab.worker.is_alive())
    def test_restart_marks_pending_interrupted_without_replay(self):
        run = self.submit()
        self.store.recover()
        self.assertEqual("interrupted", self.store.get("run", run["id"])["status"])
        self.assertEqual(run["id"], self.submit()["id"])
    def test_failed_parser_keeps_ocr_and_separate_run(self):
        self.engines.run_mlkit = lambda *args: {"status": "failed", "error": "InvalidOutput", "stage": "parser", "ocrPages": [{"lines": [{"rawText": "讀得到的字"}]}]}
        self.lab.start()
        first = self.wait_run(self.submit()["id"])
        second = self.wait_run(self.submit(requestId="retry")["id"])
        self.assertNotEqual(first["id"], second["id"])
        self.assertEqual("讀得到的字", first["result"]["ocrPages"][0]["lines"][0]["rawText"])
        self.assertIsNone(first["diagnostics"]["itemCount"])
    def test_delete_preserves_shared_photo_until_last_case(self):
        other = self.store.create_case({"title": "same", "images": self.case["images"]})
        self.store.delete_case(self.case["id"])
        self.assertEqual(image_bytes(), self.store.image_bytes(self.image["id"]))
        self.store.delete_case(other["id"])
        self.assertFalse((self.store.root / "images" / self.image["id"]).exists())
    def test_active_case_cannot_be_deleted(self):
        self.submit()
        with self.assertRaises(LabError): self.store.delete_case(self.case["id"])


class OutputTests(unittest.TestCase):
    def test_cloud_numbers_reject_implicit_conversion_and_overflow(self):
        for bad in (1.5, True, "-1", "1.00", "NaN", str(2**63)):
            value = candidate();value["items"][0]["lineTotalMinor"] = bad
            with self.assertRaises(LabError): validate_candidate(value)
        self.assertEqual("100", validate_candidate(candidate())["items"][0]["lineTotalMinor"])
    def test_missing_currency_and_quantity_stay_unknown(self):
        value = candidate();value["currency"] = None;value["items"][0]["quantity"] = None
        checked = validate_candidate(value)
        self.assertIsNone(checked["items"][0]["quantity"])
        self.assertEqual("not_evaluated", diagnostics({"candidate": checked})["accuracy"])
    def test_gemini_request_is_bound_and_truncated_output_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict('os.environ', {"GEMINI_API_KEY": "test-key-only"}):
            store = Store(temp);image = store.upload(image_bytes(), "test.png")
            case = store.create_case({"title": "test", "images": [image["id"]]})
            engines = Engines(model="gemini-test")
            consent = {"provider": "google-gemini", "model": "gemini-test", "purpose": "receipt-extraction", "imageHashes": case["images"]}
            raw = {"candidates": [{"finishReason": "MAX_TOKENS", "content": {"parts": [{"text": "{incomplete"}]}}]}
            with patch('urllib.request.build_opener') as mock:
                mock.return_value.open.return_value = io.BytesIO(json.dumps(raw).encode())
                result = engines.run_gemini({"consent": consent}, case, store)
                request = mock.return_value.open.call_args.args[0]
                self.assertEqual("test-key-only", request.headers["X-goog-api-key"])
                self.assertIn("inlineData", request.data.decode())
                self.assertNotIn("test-key-only", request.full_url)
            self.assertEqual("failed", result["status"])
            self.assertEqual(raw, result["rawResponse"])
            self.assertNotIn("test-key-only", json.dumps(result))
    def test_physical_device_is_never_accepted(self):
        with self.assertRaises(LabError): Engines(serial="57281FDCG001E1")


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.lab = Lab(Store(self.temp.name), FixtureEngines())
        self.server = Server(("127.0.0.1", 0), self.lab)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start();self.lab.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.lab.stop();self.lab.worker.join(2);self.temp.cleanup()
    def request(self, path, value=None, method=None, headers=None, body=None):
        hdr = {"X-Receipt-Lab": "1", **(headers or {})}
        if value is not None: body=json.dumps(value).encode();hdr["Content-Type"]="application/json"
        with urllib.request.urlopen(urllib.request.Request(self.url+path,data=body,method=method,headers=hdr)) as r:
            return r.read()
    def test_actual_http_upload_run_note_export(self):
        image=json.loads(self.request('/api/images?name=test.png',method='POST',body=image_bytes()))
        case=json.loads(self.request('/api/cases',{"title":"<script>untrusted</script>","images":[image['id']]}))
        run=json.loads(self.request(f"/api/cases/{case['id']}/runs",{"requestId":"http1","engine":"mlkit"}))
        deadline=time.monotonic()+5
        while time.monotonic()<deadline:
            result=json.loads(self.request('/api/runs/'+run['id']))
            if result['status'] in {'succeeded','failed'}:break
            time.sleep(.02)
        self.assertEqual('succeeded',result['status'])
        self.request(f"/api/cases/{case['id']}/notes",{"text":"API analysis"})
        exported=json.loads(self.request('/api/cases/'+case['id']))
        self.assertEqual('API analysis',exported['notes'][0]['text'])
        self.assertEqual(image_bytes(),self.request('/api/images/'+image['id']))
        self.assertIn(b'PixelReceipt',self.request('/'))
    def test_cross_origin_dns_rebinding_and_malformed_body(self):
        for headers in ({'Origin':'https://evil.example'},{'Host':'evil.example'},{'Sec-Fetch-Site':'cross-site'}):
            with self.assertRaises(urllib.error.HTTPError) as error:self.request('/api/health',headers=headers)
            self.assertEqual(403,error.exception.code)
            error.exception.close()
        with self.assertRaises(urllib.error.HTTPError) as error:self.request('/api/cases',method='POST',body=b'{}',headers={'X-Receipt-Lab':'0'})
        self.assertEqual(403,error.exception.code)
        error.exception.close()
        with self.assertRaises(urllib.error.HTTPError) as error:self.request('/api/cases',value=[])
        self.assertEqual(400,error.exception.code)
        error.exception.close()
        with self.assertRaises(urllib.error.HTTPError) as error:self.request('/../../.env')
        error.exception.close()


if __name__ == '__main__':
    unittest.main()
