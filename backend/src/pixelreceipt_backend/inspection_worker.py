"""Private subprocess protocol. Set Linux limits before importing image/PDF parsers."""

import json
import sys

CPU_SOFT_SECONDS = 5
CPU_HARD_SECONDS = 6
ADDRESS_SPACE_BYTES = 384 * 1024 * 1024
MAX_FILE_DESCRIPTORS = 32
MAX_INPUT_BYTES = 20 * 1024 * 1024
MEDIA_TYPES = {"image/jpeg", "image/png", "image/webp", "application/pdf"}


def _limits():
    if sys.platform != "linux":
        raise RuntimeError
    import resource

    resource.setrlimit(resource.RLIMIT_CPU, (CPU_SOFT_SECONDS, CPU_HARD_SECONDS))
    resource.setrlimit(resource.RLIMIT_AS, (ADDRESS_SPACE_BYTES, ADDRESS_SPACE_BYTES))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_NOFILE, (MAX_FILE_DESCRIPTORS, MAX_FILE_DESCRIPTORS))


def main():
    try:
        _limits()
        from .evidence import InvalidEvidence, inspect_evidence

        if len(sys.argv) != 2 or sys.argv[1] not in MEDIA_TYPES:
            raise InvalidEvidence("unsupported_media_type")
        content = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
        try:
            metadata = inspect_evidence(content, sys.argv[1])
        except InvalidEvidence:
            sys.stdout.write('{"ok":false,"error":"invalid_evidence"}\n')
            return 2
        sys.stdout.write(
            json.dumps({"ok": True, "metadata": metadata}, separators=(",", ":")) + "\n"
        )
        return 0
    except BaseException:
        # Resource failures/import/decoder faults never emit receipt data or tracebacks.
        return 3


if __name__ == "__main__":
    sys.exit(main())
