import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from contextlib import contextmanager
from pathlib import Path

from .engines import Engines
from .server import Lab, Server
from .store import LabError, Store


@contextmanager
def service_lock(root):
    root.mkdir(parents=True, exist_ok=True)
    with (root / "service.lock").open("a+b") as file:
        file.seek(0)
        file.write(b"1")
        file.flush()
        file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise LabError("同一測試資料夾已有服務使用中。") from error
        try:
            yield
        finally:
            file.seek(0)
            if os.name == "nt":
                msvcrt.locking(file.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(file, fcntl.LOCK_UN)


def api(base, path, method="GET", value=None, body=None):
    headers = {"X-Receipt-Lab": "1"}
    if value is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(value, ensure_ascii=False).encode()
    request = urllib.request.Request(base.rstrip("/") + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise LabError(error.read().decode("utf-8", "replace"), error.code) from error


def main():
    parser = argparse.ArgumentParser(description="PixelReceipt 測試介面與 API client")
    parser.add_argument("--url", default="http://127.0.0.1:8765")
    commands = parser.add_subparsers(dest="command", required=True)
    serve = commands.add_parser("serve")
    serve.add_argument("--port", type=int, default=8765)
    serve.add_argument("--data-dir", type=Path, default=Path(".receipt-lab"))
    serve.add_argument("--adb", default=os.environ.get("RECEIPT_LAB_ADB"))
    serve.add_argument("--serial", default="emulator-5584")
    serve.add_argument("--model", default=None)
    commands.add_parser("status")
    commands.add_parser("list")
    upload = commands.add_parser("upload", help="一張一筆；--same-receipt 將多頁放同筆")
    upload.add_argument("photos", nargs="+", type=Path)
    upload.add_argument("--same-receipt", action="store_true")
    upload.add_argument("--run", action="store_true", help="上傳後執行本機 OCR")
    run = commands.add_parser("run")
    run.add_argument("case_id")
    run.add_argument("--engine", choices=["mlkit", "gemini"], default="mlkit")
    run.add_argument("--request-id", default=None)
    run.add_argument("--allow-google-upload", action="store_true", help="明確同意這筆案例全部照片送至已設定的 Google Gemini 模型")
    result = commands.add_parser("result")
    result.add_argument("run_id")
    case = commands.add_parser("case")
    case.add_argument("case_id")
    note = commands.add_parser("note")
    note.add_argument("case_id")
    note.add_argument("--file", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "serve":
        with service_lock(args.data_dir.resolve()):
            lab = Lab(Store(args.data_dir), Engines(args.adb, args.serial, args.model))
            server = Server(("127.0.0.1", args.port), lab)
            lab.start()
            print(f"PixelReceipt 測試介面：http://127.0.0.1:{server.server_port}", flush=True)
            print("照片與結果只保存在指定測試資料夾。按 Ctrl+C 停止。", flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
            finally:
                lab.stop()
                server.server_close()
        return
    if args.command == "status":
        output = api(args.url, "/api/health")
    elif args.command == "list":
        output = api(args.url, "/api/cases")
    elif args.command == "upload":
        uploaded = [(p, api(args.url, "/api/images?name=" + urllib.parse.quote(p.name), "POST", body=p.read_bytes())) for p in args.photos]
        groups = [uploaded] if args.same_receipt else [[entry] for entry in uploaded]
        output = []
        for group in groups:
            case = api(args.url, "/api/cases", "POST", {"title": group[0][0].stem, "images": list(dict.fromkeys(x[1]["id"] for x in group))})
            if args.run:
                case["run"] = api(args.url, f"/api/cases/{case['id']}/runs", "POST", {"engine": "mlkit", "requestId": uuid.uuid4().hex})
            output.append(case)
    elif args.command == "run":
        value = {"engine": args.engine, "requestId": args.request_id or uuid.uuid4().hex}
        if args.engine == "gemini":
            if not args.allow_google_upload:
                raise LabError("Gemini 會傳送這筆照片至 Google；只有已獲同意時才加 --allow-google-upload。")
            case = api(args.url, f"/api/cases/{args.case_id}")
            health = api(args.url, "/api/health")
            value["consent"] = {"provider": "google-gemini", "model": health["engines"]["gemini"]["model"],
                                "purpose": "receipt-extraction", "imageHashes": case["images"]}
        output = api(args.url, f"/api/cases/{args.case_id}/runs", "POST", value)
    elif args.command == "result":
        output = api(args.url, f"/api/runs/{args.run_id}")
    elif args.command == "case":
        output = api(args.url, f"/api/cases/{args.case_id}")
    else:
        output = api(args.url, f"/api/cases/{args.case_id}/notes", "POST", {"text": args.file.read_text(encoding="utf-8")})
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (LabError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
