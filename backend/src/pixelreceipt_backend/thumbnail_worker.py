"""Bounded model-input derivation protocol. Originals are never rewritten."""

import base64
import io
import json
import sys

from .inspection_worker import MAX_INPUT_BYTES, _limits

MAX_DERIVED_BYTES = 384 * 1024


def pack_pdf():
    from PIL import Image
    from pypdf import PdfWriter
    from pypdf.generic import (
        DecodedStreamObject,
        DictionaryObject,
        NameObject,
        NumberObject,
    )

    value = json.loads(sys.stdin.buffer.read(12 * 1024 * 1024 + 1))
    if not isinstance(value, list) or not 1 <= len(value) <= 20:
        return 2
    writer = PdfWriter()
    total = 0
    for entry in value:
        raw = base64.b64decode(entry["data"], validate=True)
        total += len(raw)
        if total > 8 * 1024 * 1024 or not 0 < len(raw) <= MAX_DERIVED_BYTES:
            return 2
        with Image.open(io.BytesIO(raw)) as image:
            if image.format != "JPEG" or image.mode != "RGB":
                return 2
            image.verify()
            width, height = image.size
        if not 1 <= width <= 1600 or not 1 <= height <= 1600:
            return 2
        page = writer.add_blank_page(width=width, height=height)
        image_stream = DecodedStreamObject()
        image_stream.set_data(raw)
        image_stream.update(
            {
                NameObject("/Type"): NameObject("/XObject"),
                NameObject("/Subtype"): NameObject("/Image"),
                NameObject("/Width"): NumberObject(width),
                NameObject("/Height"): NumberObject(height),
                NameObject("/ColorSpace"): NameObject("/DeviceRGB"),
                NameObject("/BitsPerComponent"): NumberObject(8),
                NameObject("/Filter"): NameObject("/DCTDecode"),
            }
        )
        page[NameObject("/Resources")] = DictionaryObject(
            {
                NameObject("/XObject"): DictionaryObject(
                    {
                        NameObject("/Im0"): writer._add_object(image_stream),
                    }
                ),
            }
        )
        operations = DecodedStreamObject()
        operations.set_data(f"q {width} 0 0 {height} 0 0 cm /Im0 Do Q".encode("ascii"))
        page[NameObject("/Contents")] = writer._add_object(operations)
    stream = io.BytesIO()
    writer.write(stream)
    content = stream.getvalue()
    if len(content) > 8 * 1024 * 1024:
        return 2
    sys.stdout.write(
        json.dumps(
            {"data": base64.b64encode(content).decode("ascii"), "page_count": len(value)},
            separators=(",", ":"),
        )
    )
    return 0


def main():
    try:
        _limits()
        from PIL import Image, ImageOps

        from .evidence import inspect_evidence

        if len(sys.argv) == 2 and sys.argv[1] == "document_pdf":
            return pack_pdf()
        if len(sys.argv) != 2 or sys.argv[1] not in {"image/png", "image/jpeg", "image/webp"}:
            return 2
        content = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
        inspect_evidence(content, sys.argv[1])
        with Image.open(io.BytesIO(content)) as image:
            # Resize while decoding before EXIF/RGB copies, keeping full-size phone
            # images within the child address-space bound. Originals were decoded above.
            image.thumbnail((1600, 1600))
            image = ImageOps.exif_transpose(image).convert("RGB")
            while True:
                for quality in (85, 70, 55):
                    stream = io.BytesIO()
                    image.save(stream, format="JPEG", quality=quality, optimize=False)
                    derived = stream.getvalue()
                    if len(derived) <= MAX_DERIVED_BYTES:
                        sys.stdout.write(
                            json.dumps(
                                {
                                    "data": base64.b64encode(derived).decode("ascii"),
                                    "width": image.width,
                                    "height": image.height,
                                },
                                separators=(",", ":"),
                            )
                        )
                        return 0
                image.thumbnail((max(1, image.width * 3 // 4), max(1, image.height * 3 // 4)))
    except BaseException:
        return 3


if __name__ == "__main__":
    sys.exit(main())
