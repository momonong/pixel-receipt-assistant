"""One-at-a-time, resource-bounded Linux subprocess inspection; not a full sandbox."""

import asyncio
import json
import os
import signal
import sys
import threading

from .evidence import (
    MAX_PDF_PAGES,
    MAX_PIXELS,
    MAX_SIDE,
    MAX_UPLOAD_BYTES,
    SUPPORTED_MEDIA,
    InvalidEvidence,
)

WALL_TIMEOUT_SECONDS = 10
MAX_METADATA_BYTES = 4096
_SLOT = threading.Lock()


class InspectionUnavailable(RuntimeError):
    """Map to HTTP 503 without exposing parser output, filenames or credentials."""

    status = 503

    def __init__(self, code="inspection_failed"):
        self.code = code
        super().__init__(code)


async def _spawn(media_type):
    return await asyncio.create_subprocess_exec(
        sys.executable,
        "-I",
        "-m",
        "pixelreceipt_backend.inspection_worker",
        media_type,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
        env={"PATH": os.defpath, "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"},
        cwd="/",
        start_new_session=True,
        limit=MAX_METADATA_BYTES + 1,
    )


async def _finish_even_if_cancelled(task, *, propagate=False):
    """Cleanup must finish even if a disconnected request is cancelled repeatedly."""
    cancelled = False
    while not task.done():
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            cancelled = True
    if cancelled and propagate:
        # Finish/reap first, then preserve cancellation of the caller.
        task.result()
        raise asyncio.CancelledError
    return task.result()


async def _reap(process):
    if process.returncode is None:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    await process.wait()


async def _exchange(process, content, max_output_bytes=MAX_METADATA_BYTES):
    async def feed():
        try:
            process.stdin.write(content)
            await process.stdin.drain()
        except (BrokenPipeError, ConnectionResetError):
            # A rejected input or resource-limited process may close stdin early.
            pass
        finally:
            process.stdin.close()

    async def receive():
        output = bytearray()
        while chunk := await process.stdout.read(1024):
            output.extend(chunk)
            if len(output) > max_output_bytes:
                raise InspectionUnavailable("inspection_protocol_error")
        return bytes(output)

    tasks = [
        asyncio.create_task(feed()),
        asyncio.create_task(receive()),
        asyncio.create_task(process.wait()),
    ]
    try:
        _, output, exit_code = await asyncio.gather(*tasks)
        return output, exit_code
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)


def _metadata(output, exit_code, media_type):
    if exit_code not in {0, 2}:
        raise InspectionUnavailable("inspection_failed")
    try:
        envelope = json.loads(output)
    except (ValueError, UnicodeError, RecursionError):
        raise InspectionUnavailable("inspection_protocol_error") from None
    if exit_code == 2 and envelope == {"ok": False, "error": "invalid_evidence"}:
        raise InvalidEvidence("invalid_or_unsupported_evidence")
    if exit_code != 0:
        raise InspectionUnavailable("inspection_failed")
    if (
        not isinstance(envelope, dict)
        or set(envelope) != {"ok", "metadata"}
        or envelope["ok"] is not True
    ):
        raise InspectionUnavailable("inspection_protocol_error")
    value = envelope["metadata"]
    if (
        not isinstance(value, dict)
        or set(value) != {"media_type", "page_count", "width", "height"}
        or value["media_type"] != media_type
        or type(value["page_count"]) is not int
        or not 1 <= value["page_count"] <= MAX_PDF_PAGES
    ):
        raise InspectionUnavailable("inspection_protocol_error")
    if media_type == "application/pdf":
        valid = value["width"] is None and value["height"] is None
    else:
        valid = (
            value["page_count"] == 1
            and type(value["width"]) is int
            and type(value["height"]) is int
            and 1 <= value["width"] <= MAX_SIDE
            and 1 <= value["height"] <= MAX_SIDE
            and value["width"] * value["height"] <= MAX_PIXELS
        )
    if not valid:
        raise InspectionUnavailable("inspection_protocol_error")
    return value


async def inspect_evidence_isolated(content: bytes, media_type: str) -> dict:
    """Inspect original bytes. Invalid input is 422; unavailable/busy/timeout is 503.

    No queue and no fallback. The process-local slot spans spawn, exchange and reap.
    Linux worker limits: CPU soft 5s/hard 6s, address space 384MiB, no core dumps,
    32 file descriptors. Parent wall limit 10s. Those limits are not a filesystem
    or network sandbox and may reject expensive, otherwise valid documents.
    """
    if media_type not in SUPPORTED_MEDIA or not content or len(content) > MAX_UPLOAD_BYTES:
        raise InvalidEvidence("invalid_or_unsupported_evidence")
    if sys.platform != "linux":
        raise InspectionUnavailable("inspection_requires_linux")
    if not _SLOT.acquire(blocking=False):
        raise InspectionUnavailable("inspection_busy")
    process = None
    spawn = None
    try:
        async with asyncio.timeout(WALL_TIMEOUT_SECONDS):
            spawn = asyncio.create_task(_spawn(media_type))
            try:
                process = await asyncio.shield(spawn)
            except asyncio.CancelledError:
                # Cancellation during OS spawn must not lose the eventual PID.
                try:
                    process = await _finish_even_if_cancelled(spawn)
                except (OSError, ValueError):
                    pass
                raise
            output, exit_code = await _exchange(process, content)
            return _metadata(output, exit_code, media_type)
    except TimeoutError:
        raise InspectionUnavailable("inspection_timeout") from None
    except InvalidEvidence:
        raise
    except (OSError, ValueError):
        raise InspectionUnavailable("inspection_failed") from None
    finally:
        try:
            if process is not None:
                await _finish_even_if_cancelled(asyncio.create_task(_reap(process)), propagate=True)
        finally:
            _SLOT.release()
