"""Explicit physical Pixel foreground bridge. Never installs, instruments, clears, or reads Room."""
import base64
import hashlib
import json
import re
import subprocess
import time
import uuid
from pathlib import Path

from .store import LabError, require

PACKAGE = "com.momonong.pixelreceipt"


class NanoDevice:
    def __init__(self, adb, serial):
        require(adb and Path(adb).is_file(), "Nano 測試需要有效 ADB 路徑。")
        require(isinstance(serial, str) and re.fullmatch(r"[A-Za-z0-9]{6,40}", serial),
                "Nano 必須明確指定實體 Pixel serial；不使用預設裝置。")
        self.adb, self.serial = str(adb), serial

    def call(self, *args, body=None, timeout=30):
        result = subprocess.run([self.adb, "-s", self.serial, *args], input=body,
            capture_output=True, timeout=timeout, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        require(result.returncode == 0, result.stderr.decode("utf-8", "replace")[-1000:] or "ADB 失敗", 503)
        return result.stdout.decode("utf-8", "replace")

    def verify(self):
        require(self.call("get-state").strip() == "device", "Pixel 未連線。", 503)
        require(self.call("shell", "getprop", "ro.product.manufacturer").strip() == "Google", "指定裝置不是 Google Pixel。", 503)
        model = self.call("shell", "getprop", "ro.product.model").strip()
        require(model.startswith("Pixel ") and self.call("shell", "getprop", "ro.kernel.qemu").strip() != "1",
                "Nano 測試禁止把模擬器當實機。", 503)
        return model

    def run(self, mode, images=(), download=False, job=None, cancelled=lambda: False):
        require(mode in {"probe", "nano-image", "nano-ocr"}, "Nano 方案無效。")
        self.verify()
        apk = self.call("shell", "pm", "path", PACKAGE).strip().removeprefix("package:")
        require(re.fullmatch(r"/data/app/[a-zA-Z0-9_./=+~\-]+\.apk", apk), "無法識別測試 APK。", 503)
        apk_hash = self.call("shell", "sha256sum", apk).split()[0]
        require(re.fullmatch(r"[a-f0-9]{64}", apk_hash), "無法核對測試 APK hash。", 503)
        job = job or uuid.uuid4().hex
        require(re.fullmatch(r"[a-f0-9]{32}", job), "測試 ID 無效。")
        require(mode == "probe" and not images or len(images) == 1, "每次只處理一張本次授權收據。")
        remote = f"files/nano-lab/{job}"
        self.call("shell", "run-as", PACKAGE, "mkdir", "-p", remote)
        for index, (digest, body) in enumerate(images):
            require(re.fullmatch(r"[a-f0-9]{64}", digest), "圖片 hash 無效。")
            require(hashlib.sha256(body).hexdigest() == digest, "原圖 hash 不符，未傳送。")
            self.call("shell", "-T", "run-as", PACKAGE, "tee", f"{remote}/{index}.image.b64", body=base64.b64encode(body))
        manifest = {"mode": mode, "hashes": [x[0] for x in images], "download": download}
        self.call("shell", "-T", "run-as", PACKAGE, "tee", f"{remote}/input.json", body=json.dumps(manifest).encode())
        launch = self.call("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.lab.NanoLabActivity", "--es", "job", job)
        require("Error" not in launch and "Status: ok" in launch, "Nano 前景診斷頁未成功開啟；請安裝本任務 debug 版本。", 503)
        deadline = time.monotonic() + 255
        cancel_sent = False
        while time.monotonic() < deadline:
            if cancelled() and not cancel_sent:
                self.call("shell", "-T", "run-as", PACKAGE, "tee", f"{remote}/cancel", body=b"cancel")
                cancel_sent = True
            ready = self.call("shell", "run-as", PACKAGE, "ls", remote)
            if "result.json" in ready.split():
                raw = self.call("exec-out", "run-as", PACKAGE, "cat", f"{remote}/result.json")
                require(len(raw.encode()) <= 2 * 1024 * 1024, "Nano 結果過大。")
                result = json.loads(raw)
                require(result.get("jobId") == job and result.get("schemaVersion") == 1, "Nano 回傳不同次結果。")
                require(mode == "probe" or result.get("inputHashes", manifest["hashes"]) == manifest["hashes"], "Nano 輸入 hash 不符。")
                result["transport"] = "explicit_pixel_debug_activity_no_room"
                result["serial"] = self.serial
                result["installedApkSha256"] = {PACKAGE: apk_hash}
                return result
            time.sleep(1)
        raise LabError(f"Nano 測試逾時；沒有自動重跑。結果位置 {remote}", 503)
