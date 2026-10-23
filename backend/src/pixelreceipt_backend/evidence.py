"""Bounded format inspection; the original bytes are never rewritten."""

import io
import warnings

from PIL import Image, UnidentifiedImageError
from pypdf import PdfReader
from pypdf.errors import PdfReadError

MAX_UPLOAD_BYTES = 20 * 1024 * 1024
MAX_PIXELS = 50_000_000
MAX_SIDE = 20_000
MAX_PDF_PAGES = 100
MEDIA_FORMATS = {"image/jpeg": "JPEG", "image/png": "PNG", "image/webp": "WEBP"}
SUPPORTED_MEDIA = {*MEDIA_FORMATS, "application/pdf"}


class InvalidEvidence(ValueError):
    pass


def inspect_evidence(content: bytes, media_type: str) -> dict:
    if not content or len(content) > MAX_UPLOAD_BYTES:
        raise InvalidEvidence("evidence_size")
    if media_type == "application/pdf":
        if not content.startswith(b"%PDF-"):
            raise InvalidEvidence("invalid_pdf")
        try:
            reader = PdfReader(io.BytesIO(content), strict=True)
            if reader.is_encrypted:
                raise InvalidEvidence("encrypted_pdf")
            pages = len(reader.pages)
            if not 1 <= pages <= MAX_PDF_PAGES:
                raise InvalidEvidence("pdf_page_limit")
            # This is storage, not a PDF viewer. Active content/attachments are not accepted.
            root = reader.trailer["/Root"]
            if any(key in root for key in ("/OpenAction", "/AA", "/AcroForm")):
                raise InvalidEvidence("active_pdf")
            names = root.get("/Names", {})
            if any(key in names for key in ("/JavaScript", "/EmbeddedFiles")):
                raise InvalidEvidence("active_pdf")
            for page in reader.pages:
                if any(key in page for key in ("/AA", "/Annots")):
                    raise InvalidEvidence("active_pdf")
        except (PdfReadError, ValueError, KeyError, TypeError, RecursionError) as error:
            raise InvalidEvidence("invalid_or_unsupported_pdf") from error
        return {"media_type": media_type, "page_count": pages, "width": None, "height": None}
    if media_type not in MEDIA_FORMATS:
        raise InvalidEvidence("unsupported_media_type")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(content)) as image:
                width, height = image.size
                if image.format != MEDIA_FORMATS[media_type]:
                    raise InvalidEvidence("media_type_mismatch")
                if (
                    width * height > MAX_PIXELS
                    or max(width, height) > MAX_SIDE
                    or getattr(image, "n_frames", 1) != 1
                ):
                    raise InvalidEvidence("image_limits")
                image.verify()
            # verify() alone does not fully decode JPEG; reject truncated/corrupt pixel data too.
            with Image.open(io.BytesIO(content)) as image:
                image.load()
    except (
        UnidentifiedImageError,
        OSError,
        ValueError,
        SyntaxError,
        Image.DecompressionBombError,
        Image.DecompressionBombWarning,
    ) as error:
        raise InvalidEvidence("invalid_image") from error
    return {"media_type": media_type, "page_count": 1, "width": width, "height": height}
