"""Text lines from a small PDF. Pure: bytes in, lines out, with limits for untrusted files.

The fetcher already caps the download size; this caps the pages read.
"""

import io

import pdfplumber
from pdfminer.pdfparser import PDFSyntaxError
from pdfminer.psparser import PSException
from pdfplumber.utils.exceptions import MalformedPDFException, PdfminerException

from settlecal_pipeline.sources.base import ParseError
from settlecal_pipeline.sources.html_tables import clean

MAX_PAGES = 5


def pdf_lines(content: bytes, url: str, max_pages: int = MAX_PAGES) -> list[str]:
    """Non-empty lines of text, whitespace collapsed, in reading order."""
    try:
        with pdfplumber.open(io.BytesIO(content)) as pdf:
            if len(pdf.pages) > max_pages:
                raise ParseError(f"{url} has {len(pdf.pages)} pages; at most {max_pages} are read")
            text = "\n".join(page.extract_text() or "" for page in pdf.pages)
    except (PdfminerException, MalformedPDFException, PDFSyntaxError, PSException) as error:
        raise ParseError(f"{url} is not a readable PDF") from error
    return [line for line in (clean(raw) for raw in text.splitlines()) if line]
