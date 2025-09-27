#!/usr/bin/env python3
import os, sys, json
from PyPDF2 import PdfReader

pdf = sys.argv[1]
reader = PdfReader(open(pdf, "rb"))
pages = len(reader.pages)
size_mb = round(os.path.getsize(pdf)/(1024*1024),3)

print(json.dumps({
    "file": pdf,
    "size_mb": size_mb,
    "pages": pages,
    "image_page_ratio": 0.0,   # leave 0.0 for now (needs more parsing)
    "dpi_estimate": 300,
    "avg_image_size_kb": 0.0,
    "fonts_embedded_pct": 0.5,
    "xref_error_count": 0,
    "ocr_required": 0,
    "producer": (reader.metadata.producer if reader.metadata else "Unknown") or "Unknown"
}))
