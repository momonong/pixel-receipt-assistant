import hashlib
import json
import mimetypes
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from .engines import PROMPT, schema
from .store import LabError, MAX_IMAGE, diagnostics, now, require

STATIC = Path(__file__).parent / "static"
TERMINAL = {"succeeded", "failed", "cancelled", "interrupted"}


class Lab:
    def __init__(self, store, engines):
        self.store, self.engines = store, engines
        self.stopping = threading.Event()
        self.wakeup = threading.Event()
        self.worker = None
        self.active_case = None

    def start(self):
        self.store.recover()
        self.worker = threading.Thread(target=self.work, name="receipt-lab-worker", daemon=True)
        self.worker.start()

    def stop(self):
        self.stopping.set()
        self.wakeup.set()

    def submit(self, case_id, value):
        case = self.store.get("case", case_id)
        engine = value.get("engine", "mlkit")
        require(engine in {"mlkit", "gemini", "nano-image", "nano-ocr"}, "不支援的辨識引擎。")
        request_id = value.get("requestId")
        require(isinstance(request_id, str) and 1 <= len(request_id) <= 100, "請提供 requestId，避免重複送出。")
        consent = value.get("consent")
        if engine == "gemini":
            require(consent == {"provider": "google-gemini", "model": self.engines.model,
                "purpose": "receipt-extraction", "imageHashes": case["images"]}, "請明確同意本次指定照片傳送至 Google Gemini。", 403)
        signature = {"caseId": case_id, "engine": engine, "model": self.engines.model if engine == "gemini" else None,
                     "imageHashes": case["images"], "consent": consent}
        if engine.startswith("nano-"):
            require(len(case["images"]) == 1, "Nano 比較工具每次只接受一張收據。")
            signature["device"] = self.engines.nano_serial
        with self.store.lock:
            for existing in self.store.all("run"):
                if existing["requestId"] == request_id:
                    require(existing["request"] == signature, "相同 requestId 已用於不同內容。", 409)
                    return existing
            require(self.engines.capabilities()[engine]["configured"], "此引擎尚未設定，沒有送出照片。", 503)
            require(sum(r["status"] in {"queued", "running"} for r in self.store.all("run")) < 30, "等待中的測試已達 30 筆。", 429)
            run = self.store.put("run", {"id": uuid.uuid4().hex, "caseId": case_id, "engine": engine,
                "requestId": request_id, "request": signature, "consent": consent, "status": "queued",
                "createdAt": now(), "result": None, "diagnostics": None,
                "promptSha256": hashlib.sha256(PROMPT.encode()).hexdigest() if engine == "gemini" else None})
            self.wakeup.set()
            return run

    def cancel(self, identity):
        with self.store.lock:
            run = self.store.get("run", identity)
            if run["status"] not in TERMINAL:
                run.update(status="cancelled", finishedAt=now(), error="已取消接收結果；若請求已送出，遠端可能仍完成處理及計費。" if run["engine"] == "gemini" else "已取消接收結果；本次不套用晚到輸出。")
                self.store.put("run", run)
            return run

    def delete_case(self, identity):
        with self.store.lock:
            # Cancellation discards output, but the adapter may still be reading images.
            require(identity != self.active_case, "本次辨識仍在結束處理，請稍後再刪除。", 409)
            self.store.delete_case(identity)

    def work(self):
        while not self.stopping.is_set():
            queued = [r for r in self.store.all("run") if r["status"] == "queued"]
            if not queued:
                self.wakeup.wait(1)
                self.wakeup.clear()
                continue
            run = queued[-1]
            with self.store.lock:
                if self.store.get("run", run["id"])["status"] != "queued":
                    continue
                self.store.finish(run["id"], status="running", startedAt=now())
                self.active_case = run["caseId"]
            started = time.monotonic()
            try:
                case = self.store.get("case", run["caseId"])
                method = self.engines.run_nano if run["engine"].startswith("nano-") else self.engines.run_mlkit if run["engine"] == "mlkit" else self.engines.run_gemini
                result = method(run, case, self.store)
                require(result.get("status") in {"succeeded", "failed"}, "辨識器回傳無效狀態。")
                self.store.finish(run["id"], status=result["status"], result=result,
                    diagnostics=diagnostics(result), error=result.get("error"), finishedAt=now(),
                    elapsedMs=round((time.monotonic() - started) * 1000))
            except Exception as error:
                message = str(error)[:1500] if isinstance(error, LabError) else f"測試執行失敗（{type(error).__name__}）；沒有覆寫其他次結果。"
                self.store.finish(run["id"], status="failed", error=message, finishedAt=now(),
                                  elapsedMs=round((time.monotonic() - started) * 1000))
            finally:
                with self.store.lock:
                    self.active_case = None

    def case(self, identity):
        case = self.store.get("case", identity)
        return {**case, "imageDetails": [self.store.get("image", h) for h in case["images"]],
                "runs": [r for r in self.store.all("run") if r["caseId"] == identity]}


def api_description():
    # Small discoverable contract and examples; usable with curl/urllib without a custom connector.
    return {"version": 1, "mutatingHeader": {"X-Receipt-Lab": "1"}, "routes": [
        {"method": "GET", "path": "/api/health", "description": "本機／雲端設定狀態，不回傳 API key"},
        {"method": "POST", "path": "/api/images?name=receipt.png", "body": "raw JPEG/PNG/WebP bytes", "response": "image.id is SHA-256"},
        {"method": "POST", "path": "/api/cases", "body": {"title": "收據", "images": ["SHA256"], "dataset": "development"}},
        {"method": "GET", "path": "/api/cases", "description": "案例、最新狀態與持久化分析"},
        {"method": "GET", "path": "/api/cases/{id}", "description": "原圖 metadata、所有次測試與 raw output"},
        {"method": "GET", "path": "/api/images/{sha256}", "description": "指定已上傳照片的原始 bytes"},
        {"method": "POST", "path": "/api/cases/{id}/runs", "body": {"engine": "mlkit", "requestId": "unique-request-id"}},
        {"method": "POST", "path": "/api/cases/{id}/runs", "body": {"engine": "nano-image", "requestId": "unique-request-id"}, "description": "B: one image on explicitly configured physical Pixel"},
        {"method": "POST", "path": "/api/cases/{id}/runs", "body": {"engine": "nano-ocr", "requestId": "unique-request-id"}, "description": "C: image plus actual OCR on explicitly configured physical Pixel"},
        {"method": "POST", "path": "/api/cases/{id}/runs", "body": {"engine": "gemini", "requestId": "unique-request-id", "consent": {"provider": "google-gemini", "model": "configured-model", "purpose": "receipt-extraction", "imageHashes": ["exact-ordered-SHA256-list"]}}},
        {"method": "GET", "path": "/api/runs/{id}", "description": "queued/running/succeeded/failed/cancelled/interrupted"},
        {"method": "POST", "path": "/api/runs/{id}/cancel", "body": {}},
        {"method": "POST", "path": "/api/cases/{id}/notes", "body": {"text": "分析、錯誤定位、比較結論；不是人工真值"}},
        {"method": "DELETE", "path": "/api/cases/{id}", "description": "移除案例、結果及沒有其他案例使用的照片"}],
        "cloudSchema": schema(), "accuracy": "No ground truth supplied: accuracy is not evaluated."}


class Server(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, lab):
        super().__init__(address, Handler)
        self.lab = lab


class Handler(BaseHTTPRequestHandler):
    server_version = "ReceiptLab/1"

    def log_message(self, *args):
        pass  # No receipt names, contents, API keys or URLs in HTTP logs.

    def guard(self, mutation=False):
        allowed = {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}
        require(self.headers.get("Host") in allowed, "只接受本機測試服務位址。", 403)
        origin = self.headers.get("Origin")
        require(origin is None or origin in {f"http://{x}" for x in allowed}, "不接受其他網站的請求。", 403)
        require(self.headers.get("Sec-Fetch-Site") != "cross-site", "不接受跨網站請求。", 403)
        if mutation:
            require(self.headers.get("X-Receipt-Lab") == "1", "缺少 X-Receipt-Lab: 1。", 403)

    def send(self, status, body, content_type="application/json; charset=utf-8"):
        encoded = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' blob:; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")
        self.end_headers()
        self.wfile.write(encoded)

    def body(self, maximum=100_000):
        require(not self.headers.get("Transfer-Encoding"), "請提供 Content-Length。", 400)
        try:
            size = int(self.headers.get("Content-Length", "-1"))
        except ValueError as error:
            raise LabError("Content-Length 無效。") from error
        require(0 <= size <= maximum, "請求內容超過大小限制。", 413)
        self.connection.settimeout(30)
        data = self.rfile.read(size)
        require(len(data) == size, "上傳未完成。")
        return data

    def json_body(self):
        require(self.headers.get_content_type() == "application/json", "請使用 application/json。", 415)
        value = json.loads(self.body())
        require(isinstance(value, dict), "請求必須是 JSON 物件。")
        return value

    def route(self, method):
        try:
            self.guard(method != "GET")
            parsed = urlsplit(self.path)
            parts = parsed.path.strip("/").split("/")
            lab = self.server.lab
            if method == "GET":
                if parsed.path in {"/", "/index.html", "/app.js", "/style.css"}:
                    file = STATIC / ("index.html" if parsed.path == "/" else parsed.path[1:])
                    return self.send(200, file.read_bytes(), (mimetypes.guess_type(file.name)[0] or "text/plain") + "; charset=utf-8")
                if parts == ["api", "health"]:
                    return self.send(200, {"status": "ok", "engines": lab.engines.capabilities(), "apiVersion": 1})
                if parts == ["api", "schema"]:
                    return self.send(200, api_description())
                if parts == ["api", "cases"]:
                    summaries = []
                    runs = lab.store.all("run")
                    for case in lab.store.all("case"):
                        summaries.append({**case, "runs": [{k: v for k, v in r.items() if k != "result"} for r in runs if r["caseId"] == case["id"]]})
                    return self.send(200, {"cases": summaries})
                if len(parts) == 3 and parts[:2] == ["api", "cases"]:
                    return self.send(200, lab.case(parts[2]))
                if len(parts) == 3 and parts[:2] == ["api", "runs"]:
                    return self.send(200, lab.store.get("run", parts[2]))
                if len(parts) == 3 and parts[:2] == ["api", "images"]:
                    meta = lab.store.get("image", parts[2])
                    return self.send(200, lab.store.image_bytes(parts[2]), meta["mime"])
            if method == "POST":
                if parts == ["api", "images"]:
                    name = parse_qs(parsed.query).get("name", ["receipt"])[0]
                    return self.send(201, lab.store.upload(self.body(MAX_IMAGE), name))
                value = self.json_body()
                if parts == ["api", "cases"]:
                    return self.send(201, lab.store.create_case(value))
                if len(parts) == 4 and parts[:2] == ["api", "cases"]:
                    if parts[3] == "runs":
                        return self.send(202, lab.submit(parts[2], value))
                    if parts[3] == "notes":
                        return self.send(201, lab.store.note(parts[2], value.get("text")))
                if len(parts) == 4 and parts[:2] == ["api", "runs"] and parts[3] == "cancel":
                    return self.send(200, lab.cancel(parts[2]))
            if method == "DELETE" and len(parts) == 3 and parts[:2] == ["api", "cases"]:
                lab.delete_case(parts[2])
                return self.send(200, {"deleted": True})
            raise LabError("找不到此 API。", 404)
        except LabError as error:
            self.send(error.status, {"error": str(error)})
        except (ValueError, UnicodeError, TypeError):
            self.send(400, {"error": "請求格式無效。"})
        except (ConnectionError, TimeoutError):
            return
        except Exception:
            self.send(500, {"error": "服務處理失敗；現有收據與測試結果仍保留。"})

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_DELETE(self):
        self.route("DELETE")
