"""Real bounded child processes, synthetic bytes, and explicit cleanup/busy checks."""

import asyncio
import io
import json
import os
import subprocess
import sys

import pytest
from pypdf import PdfWriter

from pixelreceipt_backend import inspection
from pixelreceipt_backend.evidence import InvalidEvidence

pytestmark = pytest.mark.skipif(sys.platform != "linux", reason="Linux resource isolation contract")


def assert_reaped(process):
    assert process.returncode is not None
    with pytest.raises(ChildProcessError):
        os.waitpid(process.pid, os.WNOHANG)


def controlled_spawn(monkeypatch, script, processes, ready=None, *, delayed_handle=False):
    async def spawn(_media_type):
        process = await asyncio.create_subprocess_exec(
            sys.executable,
            "-I",
            "-c",
            script,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            start_new_session=True,
            env={"PATH": os.defpath, "LANG": "C.UTF-8"},
            cwd="/",
        )
        processes.append(process)
        if ready is not None:
            ready.set()
        if delayed_handle:
            await asyncio.sleep(0.1)
        return process

    monkeypatch.setattr(inspection, "_spawn", spawn)


def test_real_isolated_png_and_minimal_environment(png, monkeypatch):
    processes = []
    original_spawn = asyncio.create_subprocess_exec
    monkeypatch.setenv("PIXELRECEIPT_TOKEN", "synthetic-parent-token-not-for-child")
    monkeypatch.setenv("SYNTHETIC_PROVIDER_SECRET", "synthetic-parent-secret-not-for-child")
    monkeypatch.setenv("PYTHONPATH", "/synthetic/untrusted-import-path")

    async def observe(*args, **kwargs):
        assert args[:4] == (sys.executable, "-I", "-m", "pixelreceipt_backend.inspection_worker")
        assert set(kwargs["env"]) == {"PATH", "LANG", "LC_ALL"}
        assert "PIXELRECEIPT_TOKEN" not in kwargs["env"]
        assert "SYNTHETIC_PROVIDER_SECRET" not in kwargs["env"]
        assert "PYTHONPATH" not in kwargs["env"]
        assert kwargs["cwd"] == "/"
        assert kwargs["stderr"] == asyncio.subprocess.DEVNULL
        process = await original_spawn(*args, **kwargs)
        processes.append(process)
        return process

    monkeypatch.setattr(inspection.asyncio, "create_subprocess_exec", observe)
    metadata = asyncio.run(inspection.inspect_evidence_isolated(png, "image/png"))
    assert metadata == {"media_type": "image/png", "page_count": 1, "width": 10, "height": 8}
    assert len(processes) == 1
    assert processes[0].returncode == 0
    assert_reaped(processes[0])


def test_real_pdf_child_preserves_page_count():
    writer = PdfWriter()
    writer.add_blank_page(width=100, height=100)
    writer.add_blank_page(width=100, height=100)
    stream = io.BytesIO()
    writer.write(stream)
    content = stream.getvalue()
    metadata = asyncio.run(inspection.inspect_evidence_isolated(content, "application/pdf"))
    assert metadata == {
        "media_type": "application/pdf",
        "page_count": 2,
        "width": None,
        "height": None,
    }
    assert content == stream.getvalue()


@pytest.mark.parametrize(
    "content,media_type",
    [(b"synthetic-invalid", "image/png"), (b"%PDF-1.7\nmalformed", "application/pdf")],
)
def test_invalid_content_is_input_rejection(content, media_type):
    with pytest.raises(InvalidEvidence, match="invalid_or_unsupported_evidence"):
        asyncio.run(inspection.inspect_evidence_isolated(content, media_type))


def test_real_worker_resource_limits():
    # Apply limits only inside a child, never to the test runner.
    code = (
        "from pixelreceipt_backend.inspection_worker import _limits; _limits(); "
        "import json,resource; print(json.dumps([resource.getrlimit(x) for x in "
        "[resource.RLIMIT_CPU,resource.RLIMIT_AS,resource.RLIMIT_CORE,resource.RLIMIT_NOFILE]]))"
    )
    result = subprocess.run(
        [sys.executable, "-I", "-c", code],
        env={"PATH": os.defpath},
        cwd="/",
        capture_output=True,
        timeout=5,
        check=True,
    )
    assert json.loads(result.stdout) == [[5, 6], [384 * 1024 * 1024] * 2, [0, 0], [32, 32]]
    assert result.stderr == b""


def test_timeout_kills_and_reaps_before_releasing_slot(png, monkeypatch):
    processes = []
    original = inspection._spawn
    controlled_spawn(monkeypatch, "import time; time.sleep(60)", processes)
    monkeypatch.setattr(inspection, "WALL_TIMEOUT_SECONDS", 0.05)
    with pytest.raises(inspection.InspectionUnavailable) as failure:
        asyncio.run(inspection.inspect_evidence_isolated(png, "image/png"))
    assert failure.value.code == "inspection_timeout"
    assert failure.value.status == 503
    assert len(processes) == 1
    assert_reaped(processes[0])
    assert processes[0].returncode < 0
    monkeypatch.setattr(inspection, "_spawn", original)
    monkeypatch.setattr(inspection, "WALL_TIMEOUT_SECONDS", 10)
    assert asyncio.run(inspection.inspect_evidence_isolated(png, "image/png"))["width"] == 10


def test_busy_is_immediate_and_cancel_reaps_real_child(png, monkeypatch):
    processes = []

    async def run():
        ready = asyncio.Event()
        controlled_spawn(monkeypatch, "import time; time.sleep(60)", processes, ready)
        first = asyncio.create_task(inspection.inspect_evidence_isolated(png, "image/png"))
        await ready.wait()
        with pytest.raises(inspection.InspectionUnavailable) as failure:
            await inspection.inspect_evidence_isolated(png, "image/png")
        assert failure.value.code == "inspection_busy"
        assert len(processes) == 1
        first.cancel()
        with pytest.raises(asyncio.CancelledError):
            await first
        assert_reaped(processes[0])

    asyncio.run(run())
    assert not inspection._SLOT.locked()


def test_repeated_cancel_during_spawn_does_not_lose_child(png, monkeypatch):
    processes = []

    async def run():
        ready = asyncio.Event()
        controlled_spawn(
            monkeypatch, "import time; time.sleep(60)", processes, ready, delayed_handle=True
        )
        task = asyncio.create_task(inspection.inspect_evidence_isolated(png, "image/png"))
        await ready.wait()
        task.cancel()
        await asyncio.sleep(0.01)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert_reaped(processes[0])

    asyncio.run(run())
    assert not inspection._SLOT.locked()


def test_oversize_stdout_stops_and_reaps_worker(png, monkeypatch):
    processes = []
    script = "import sys,time; sys.stdout.write('x'*10000); sys.stdout.flush(); time.sleep(60)"
    controlled_spawn(monkeypatch, script, processes)
    with pytest.raises(inspection.InspectionUnavailable) as failure:
        asyncio.run(inspection.inspect_evidence_isolated(png, "image/png"))
    assert failure.value.code == "inspection_protocol_error"
    assert_reaped(processes[0])
    assert not inspection._SLOT.locked()


def test_child_crash_and_start_failure_have_no_parser_output(png, monkeypatch):
    processes = []
    controlled_spawn(
        monkeypatch, "import sys; sys.stderr.write('synthetic-private'); sys.exit(7)", processes
    )
    with pytest.raises(inspection.InspectionUnavailable) as failure:
        asyncio.run(inspection.inspect_evidence_isolated(png, "image/png"))
    assert str(failure.value) == "inspection_failed"
    assert_reaped(processes[0])

    async def unavailable(_media_type):
        raise OSError("synthetic-private-path-must-not-escape")

    monkeypatch.setattr(inspection, "_spawn", unavailable)
    with pytest.raises(inspection.InspectionUnavailable) as failure:
        asyncio.run(inspection.inspect_evidence_isolated(png, "image/png"))
    assert str(failure.value) == "inspection_failed"
    assert not inspection._SLOT.locked()


@pytest.mark.parametrize(
    "metadata",
    [
        {"media_type": "image/png", "page_count": 1, "width": True, "height": 1},
        {"media_type": "image/png", "page_count": 2, "width": 1, "height": 1},
        {"media_type": "image/jpeg", "page_count": 1, "width": 1, "height": 1},
        {
            "media_type": "image/png",
            "page_count": 1,
            "width": 1,
            "height": 1,
            "path": "synthetic-private",
        },
    ],
)
def test_untrusted_worker_metadata_is_not_accepted(metadata):
    output = json.dumps({"ok": True, "metadata": metadata}).encode()
    with pytest.raises(inspection.InspectionUnavailable, match="inspection_protocol_error"):
        inspection._metadata(output, 0, "image/png")


def test_invalid_size_or_type_does_not_spawn(monkeypatch):
    async def forbidden(_media_type):
        raise AssertionError("must not spawn")

    monkeypatch.setattr(inspection, "_spawn", forbidden)
    for content, media_type in [(b"", "image/png"), (b"x", "image/gif")]:
        with pytest.raises(InvalidEvidence):
            asyncio.run(inspection.inspect_evidence_isolated(content, media_type))
