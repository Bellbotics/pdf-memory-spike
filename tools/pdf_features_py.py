#!/usr/bin/env python3
"""
Minimal PDF → feature JSON extractor using PyPDF2.

Inputs (positional):
  sys.argv[1] : Path to a single PDF file

Output:
  Writes a single JSON object to stdout (one line), containing lightweight
  features compatible with your sidecar's `PdfFeatures` schema:
    - file (str): path to the PDF
    - size_mb (float): file size in mebibytes (MiB), rounded to 3 decimals
    - pages (int): number of pages in the PDF
    - image_page_ratio (float in [0,1]): placeholder (requires deeper parsing)
    - dpi_estimate (int): placeholder (e.g., 300)
    - avg_image_size_kb (float): placeholder
    - fonts_embedded_pct (float in [0,1]): placeholder (0.5 == 50%)
    - xref_error_count (int): placeholder
    - ocr_required (int 0/1): placeholder
    - producer (str): PDF Producer metadata if available, else "Unknown"

Dependencies:
  - PyPDF2

Notes:
  - This intentionally avoids heavy parsing to keep it fast and portable.
  - For real features (image ratio, embedded fonts, etc.), extend this script
    or use PDFBox/low-level PDF parsing as needed.
"""

import os, sys, json
from PyPDF2 import PdfReader

# Path to the input PDF (positional arg).
# This script assumes the caller provides a valid path in sys.argv[1].
# (If not provided, Python will raise IndexError; upstream wrapper scripts
#  typically validate args or supply defaults.)
pdf = sys.argv[1]

# PdfReader loads the document structure and allows access to pages and metadata.
# NOTE: Using `open(pdf, "rb")` without a context manager keeps the file handle
#       open until GC. For short-lived CLI runs this is acceptable; if you prefer
#       strict resource management, wrap it in `with open(...) as fh: PdfReader(fh)`.
reader = PdfReader(open(pdf, "rb"))

# Number of pages in the PDF. PyPDF2 exposes pages via `reader.pages`.
pages = len(reader.pages)

# Compute size in MiB (mebibytes) using 1024^2. Rounded to three decimals for compactness.
size_mb = round(os.path.getsize(pdf) / (1024 * 1024), 3)

# Emit a single JSON object (one line) to stdout.
# The placeholders (image_page_ratio, dpi_estimate, etc.) are set to reasonable
# defaults and can be replaced once you implement deeper parsing:
#   - image_page_ratio: fraction of pages primarily composed of raster images
#   - dpi_estimate: rough dots-per-inch estimate from image XObjects
#   - avg_image_size_kb: average size of embedded images (KB)
#   - fonts_embedded_pct: fraction of fonts that are embedded vs referenced
#   - xref_error_count: cross-reference table anomalies, if validated
#   - ocr_required: 1 if text extraction fails and OCR is needed, else 0
#   - producer: metadata string (often set by the PDF authoring tool/library)
print(json.dumps({
    "file": pdf,
    "size_mb": size_mb,
    "pages": pages,
    "image_page_ratio": 0.0,   # placeholder: set after analyzing image-only pages
    "dpi_estimate": 300,       # placeholder: adjust if you compute real DPI
    "avg_image_size_kb": 0.0,  # placeholder: compute from image XObjects
    "fonts_embedded_pct": 0.5, # placeholder: 50% embedded as a neutral default
    "xref_error_count": 0,     # placeholder: set via a lightweight validator
    "ocr_required": 0,         # placeholder: 1 if text extraction fails
    "producer": (reader.metadata.producer if reader.metadata else "Unknown") or "Unknown"
}))
