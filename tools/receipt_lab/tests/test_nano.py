import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.receipt_lab.engines import Engines
from tools.receipt_lab.nano import NanoDevice
from tools.receipt_lab.store import LabError


class NanoBridgeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.adb = Path(self.directory.name) / "adb.exe"
        self.adb.touch()
        self.calls = []
    def tearDown(self):
        self.directory.cleanup()
    def test_explicit_pixel_does_not_relax_legacy_emulator_guard(self):
        with self.assertRaises(LabError): Engines(str(self.adb), "PIXEL123")
        for serial in (None, "", "emulator-5584", "../../device", "host:5555"):
            with self.assertRaises(LabError): NanoDevice(str(self.adb), serial)
        engines = Engines(str(self.adb), nano_serial="PIXEL123")
        self.assertEqual("emulator-5584", engines.serial)
        self.assertTrue(engines.capabilities()["nano-image"]["configured"])
        self.assertFalse(Engines(str(self.adb)).capabilities()["nano-ocr"]["configured"])
    def test_non_pixel_and_emulator_are_rejected_before_any_file_transfer(self):
        device = NanoDevice(str(self.adb), "PIXEL123")
        for manufacturer, model, qemu in (("Other", "Other", ""), ("Google", "sdk_gphone", "1"), ("Google", "Pixel Test", "1")):
            with patch.object(device, "call", side_effect=["device", manufacturer, model, qemu]) as call:
                with self.assertRaises(LabError): device.run("probe")
                self.assertFalse(any("tee" in x.args for x in call.call_args_list))
    def fake_call(self, *args, body=None, timeout=30):
        self.calls.append((args, body))
        if args == ("get-state",): return "device"
        if args[-1] == "ro.product.manufacturer": return "Google"
        if args[-1] == "ro.product.model": return "Pixel Fixture"
        if args[-1] == "ro.kernel.qemu": return ""
        if args[:3] == ("shell", "pm", "path"): return "package:/data/app/fixture/base.apk"
        if "sha256sum" in args: return "a" * 64 + " base.apk"
        if "start" in args: return "Status: ok"
        if "ls" in args: return "result.json"
        if "cat" in args: return json.dumps({"jobId": "b" * 32, "schemaVersion": 1,
            "status": "failed", "error": "fixture cancelled", "cancelled": True})
        return ""
    def test_scoped_cancel_uses_debug_activity_and_never_instrumentation_install_or_private_database(self):
        device = NanoDevice(str(self.adb), "PIXEL123")
        with patch.object(device, "call", side_effect=self.fake_call):
            result = device.run("probe", job="b" * 32, cancelled=lambda: True)
        self.assertTrue(result["cancelled"])
        self.assertEqual("a" * 64, result["installedApkSha256"]["com.momonong.pixelreceipt"])
        calls = " ".join(" ".join(args) for args, _ in self.calls)
        for prohibited in ("instrument", "install", "uninstall", "pm clear", "databases/", "files/evidence", "force-stop"):
            self.assertNotIn(prohibited, calls)
        self.assertIn(".lab.NanoLabActivity", calls)
        self.assertIn("/cancel", calls)
    def test_changed_image_hash_cannot_reach_device_or_start_inference(self):
        device = NanoDevice(str(self.adb), "PIXEL123")
        with patch.object(device, "call", side_effect=self.fake_call):
            with self.assertRaisesRegex(LabError, "原圖 hash"):
                device.run("nano-image", [(hashlib.sha256(b"original").hexdigest(), b"changed")], job="b" * 32)
        self.assertFalse(any("tee" in args or "start" in args for args, _ in self.calls))

    def test_android_reused_activity_is_not_a_successful_job_launch(self):
        device = NanoDevice(str(self.adb), "PIXEL123")
        def reused(*args, **kwargs):
            if "start" in args:
                return "Warning: Activity not started, intent has been delivered to currently running top-most instance.\nStatus: ok"
            return self.fake_call(*args, **kwargs)
        with patch.object(device, "call", side_effect=reused):
            with self.assertRaisesRegex(LabError, "未建立新作業"):
                device.run("probe", job="b" * 32)
        self.assertFalse(any("ls" in args for args, _ in self.calls))

    def test_host_timeout_requests_scoped_cancellation(self):
        device = NanoDevice(str(self.adb), "PIXEL123")
        with patch.object(device, "call", side_effect=self.fake_call), patch(
                "tools.receipt_lab.nano.time.monotonic", side_effect=[0, 0, 256]):
            with self.assertRaisesRegex(LabError, "已要求取消"):
                device.run("probe", job="b" * 32)
        self.assertTrue(any(args[-1] == "files/nano-lab/" + "b" * 32 + "/cancel"
                            and body == b"cancel" for args, body in self.calls))

    def test_unstarted_job_is_cancelled_without_waiting_for_inference_timeout(self):
        device = NanoDevice(str(self.adb), "PIXEL123")
        def not_started(*args, **kwargs):
            if "ls" in args: return "input.json"
            return self.fake_call(*args, **kwargs)
        with patch.object(device, "call", side_effect=not_started), patch(
                "tools.receipt_lab.nano.time.monotonic", side_effect=[0, 0, 1, 21]):
            with self.assertRaisesRegex(LabError, "作業未開始"):
                device.run("probe", job="b" * 32)
        self.assertTrue(any(args[-1].endswith('/cancel') for args, _ in self.calls))


if __name__ == "__main__":
    unittest.main()
