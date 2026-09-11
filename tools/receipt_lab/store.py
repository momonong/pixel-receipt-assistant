"""Small persistent experiment store. Images and completed runs are immutable."""
import hashlib
import io
import json
import sqlite3
import threading
import time
import uuid
import warnings
from contextlib import contextmanager
from pathlib import Path

from PIL import Image

MAX_IMAGE = 20 * 1024 * 1024
MAX_BATCH = 100 * 1024 * 1024
MAX_STORE = 1024 * 1024 * 1024


class LabError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def require(condition, message, status=400):
    if not condition:
        raise LabError(message, status)


def now():
    return time.time_ns() // 1_000_000


class Store:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "images").mkdir(exist_ok=True)
        self.lock = threading.RLock()
        with self.db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("CREATE TABLE IF NOT EXISTS documents (kind TEXT, id TEXT, data TEXT NOT NULL, PRIMARY KEY(kind,id))")

    @contextmanager
    def db(self):
        with self.lock:
            db = sqlite3.connect(self.root / "lab.sqlite3", timeout=10)
            try:
                with db:
                    yield db
            finally:
                db.close()

    def put(self, kind, value):
        with self.db() as db:
            db.execute("INSERT INTO documents VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET data=excluded.data",
                       (kind, value["id"], json.dumps(value, ensure_ascii=False)))
        return value

    def get(self, kind, identity):
        with self.db() as db:
            row = db.execute("SELECT data FROM documents WHERE kind=? AND id=?", (kind, identity)).fetchone()
        require(row is not None, "找不到這筆測試資料。", 404)
        return json.loads(row[0])

    def all(self, kind):
        with self.db() as db:
            rows = db.execute("SELECT data FROM documents WHERE kind=?", (kind,)).fetchall()
        return sorted((json.loads(row[0]) for row in rows), key=lambda x: x.get("createdAt", 0), reverse=True)

    def upload(self, body, name):
        require(0 < len(body) <= MAX_IMAGE, "每張圖片須介於 1 byte 與 20 MiB。", 413)
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(body)) as image:
                    require(image.format in {"PNG", "JPEG", "WEBP"}, "目前支援 JPEG、PNG、WebP。")
                    require(0 < image.width * image.height <= 50_000_000 and max(image.size) <= 20_000, "圖片尺寸超過限制。")
                    require(getattr(image, "n_frames", 1) == 1, "請上傳靜態圖片。")
                    mime = Image.MIME[image.format]
                    width, height = image.size
                    image.verify()
                with Image.open(io.BytesIO(body)) as image:
                    image.load()
        except LabError:
            raise
        except Exception as error:
            raise LabError("無法完整解碼圖片，請重新選取或轉成 JPEG／PNG。") from error
        digest = hashlib.sha256(body).hexdigest()
        with self.lock:
            existing = next((x for x in self.all("image") if x["id"] == digest), None)
            if existing:
                return existing
            require(sum(x["bytes"] for x in self.all("image")) + len(body) <= MAX_STORE, "測試照片已達 1 GiB 上限。", 413)
            path = self.root / "images" / digest
            temporary = path.with_suffix(".part")
            temporary.write_bytes(body)
            temporary.replace(path)
            return self.put("image", {"id": digest, "name": name[:200], "mime": mime, "bytes": len(body),
                                      "width": width, "height": height, "createdAt": now()})

    def image_bytes(self, identity):
        self.get("image", identity)  # No arbitrary paths: only previously validated hashes.
        body = (self.root / "images" / identity).read_bytes()
        require(hashlib.sha256(body).hexdigest() == identity, "測試原圖完整性檢查失敗。", 409)
        return body

    def create_case(self, value):
        hashes = value.get("images")
        require(isinstance(hashes, list) and 1 <= len(hashes) <= 20 and all(isinstance(x, str) for x in hashes), "請選 1–20 張照片。")
        require(len(set(hashes)) == len(hashes), "同一收據不可重複加入相同照片。")
        require(sum(self.get("image", x)["bytes"] for x in hashes) <= MAX_BATCH, "同一收據照片不可超過 100 MiB。", 413)
        title = value.get("title", "未命名收據")
        require(isinstance(title, str) and 0 < len(title.strip()) <= 200, "請提供 1–200 字的名稱。")
        purpose = value.get("dataset", "development")
        require(purpose in {"development", "acceptance"}, "樣本用途無效。")
        with self.lock:
            require(len(self.all("case")) < 500, "測試集最多 500 筆收據。")
            return self.put("case", {"id": uuid.uuid4().hex, "title": title.strip(), "images": hashes,
                                      "dataset": purpose, "createdAt": now(), "notes": []})

    def note(self, identity, text):
        require(isinstance(text, str) and 0 < len(text.strip()) <= 20_000, "分析說明須介於 1–20000 字。")
        with self.lock:
            case = self.get("case", identity)
            require(len(case["notes"]) < 100, "每筆最多保存 100 則分析。")
            case["notes"].append({"text": text.strip(), "createdAt": now(), "kind": "analysis_not_ground_truth"})
            return self.put("case", case)

    def recover(self):
        with self.lock:
            for run in self.all("run"):
                if run["status"] in {"queued", "running"}:
                    run.update(status="interrupted", error="服務中斷，未自動重送。可明確建立新一次測試。", finishedAt=now())
                    self.put("run", run)

    def finish(self, identity, **changes):
        with self.lock:
            run = self.get("run", identity)
            if run["status"] not in {"queued", "running"}:
                return run  # Cancellation or interruption cannot be overwritten by late output.
            run.update(changes)
            return self.put("run", run)

    def delete_case(self, identity):
        with self.lock:
            case = self.get("case", identity)
            runs = [r for r in self.all("run") if r["caseId"] == identity]
            require(all(r["status"] not in {"queued", "running"} for r in runs), "執行中的收據不能刪除；請先等待測試結束。", 409)
            # Includes raw output in the same DB transaction. Shared image bytes stay referenced.
            with self.db() as db:
                for run in runs:
                    db.execute("DELETE FROM documents WHERE kind='run' AND id=?", (run["id"],))
                db.execute("DELETE FROM documents WHERE kind='case' AND id=?", (identity,))
            used = {h for c in self.all("case") for h in c["images"]}
            for digest in case["images"]:
                if digest not in used:
                    (self.root / "images" / digest).unlink(missing_ok=True)
                    with self.db() as db:
                        db.execute("DELETE FROM documents WHERE kind='image' AND id=?", (digest,))


def diagnostics(result):
    candidate = result.get("candidate")
    if not candidate:
        return {"itemCount": None, "unknownFields": None, "accuracy": "not_evaluated"}
    rows = candidate["items"]
    unknown = sum(row.get(field) is None for row in rows for field in ("name", "quantity", "lineTotalMinor"))
    unknown += sum(candidate.get(field) is None for field in ("merchant", "date", "totalMinor"))
    return {"itemCount": len(rows), "unknownFields": unknown, "accuracy": "not_evaluated",
            "unresolvedAdjustments": sum(row.get("scope") is None for row in candidate.get("adjustments", [])),
            "warningCount": len(result.get("warnings", [])),
            "note": "這是擷取完整度診斷，沒有人工基準，不計算準確率或最終帳務。"}
