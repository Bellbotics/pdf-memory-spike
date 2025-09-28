#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Simple PDF → feature JSON extractor (shell + PDFBox + tiny Python snippets).
#
# Inputs:
#   $1 = path to a single PDF file
#   $2 = output path for a single-line JSON object (".json" or ".jsonl" is fine)
#
# What it does:
#   1) Uses Apache PDFBox's "PDFDebugger" tool (2.0.x JAR) to read page count.
#   2) Uses Python to compute file size in MB (precise, platform-agnostic).
#   3) Emits a minimal JSON structure with a few placeholder features
#      that match the sidecar's expected schema.
#
# Requirements:
#   - Java (to run PDFBox)
#   - tools/pdfbox-app.jar (PDFBox 2.0.x distribution JAR)
#   - python3 (available on PATH)
#
# Notes:
#   - This is intentionally lightweight; refine/extend features as needed.
#   - PDFBox 3.x changed CLI tools; this script is pinned to 2.0.x behavior.
#     If you upgrade the jar to 3.x, adjust the page-count command accordingly.
# -----------------------------------------------------------------------------

set -euo pipefail

# Positional args: the PDF to read and the JSON output file to create.
PDF="$1"
OUT="$2"

# --------------------------------------------------------------------------
# Locate the PDFBox "app" jar. We assume you've placed it at tools/pdfbox-app.jar.
# PDFBOX 2.0.x distribution bundles many CLI utilities (incl. PDFDebugger) into this JAR.
# --------------------------------------------------------------------------
JAR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$JAR_DIR/pdfbox-app.jar"

# --------------------------------------------------------------------------
# Extract the number of pages with PDFBox "PDFDebugger".
#
# Why PDFDebugger?
#   - In PDFBox 2.0.x, "PDFDebugger" prints a line like "Number of pages: <N>"
#     which we parse with grep/awk below.
#   - We redirect stderr to /dev/null because some PDFBox tools are chatty.
#
# If anything goes wrong (tool missing, parse fails), default to 0 pages.
# --------------------------------------------------------------------------
PAGES=$(java -jar "$JAR" PDFDebugger "$PDF" 2>/dev/null | grep -m1 "Number of pages:" | awk '{print $4}')
PAGES=${PAGES:-0}

# --------------------------------------------------------------------------
# Compute file size in MiB with Python.
#
# Why Python instead of 'stat'?
#   - Cross-platform consistency (macOS/Linux vary in 'stat' flags).
#   - Easy to get a rounded float with standard library only.
# --------------------------------------------------------------------------
SIZE_MB=$(python3 - <<PY
import os, sys
size_bytes = os.path.getsize(sys.argv[1])
print(round(size_bytes / (1024*1024), 3))
PY
"$PDF")

# --------------------------------------------------------------------------
# Emit a minimal JSON object on a single line to $OUT.
#
# Fields here mirror the schema expected by the sidecar's PdfFeatures model:
#   - size_mb (float)
#   - pages (int)
#   - image_page_ratio (float in [0,1])    -> crude placeholder (0.0)
#   - dpi_estimate (int)                   -> placeholder (300)
#   - avg_image_size_kb (float)            -> placeholder (0.0)
#   - fonts_embedded_pct (float in [0,1])  -> placeholder (0.5 == 50%)
#   - xref_error_count (int)               -> placeholder (0)
#   - ocr_required (int 0/1)               -> placeholder (0)
#   - producer (str)                       -> placeholder ("Unknown")
#
# You can replace placeholders with real extraction logic later:
#   - image_page_ratio: count image-only pages / total pages
#   - fonts_embedded_pct: parse font objects and check embedded flags
#   - dpi_estimate / avg_image_size_kb: derive from image XObjects
#   - xref_error_count: run a light validation pass
#   - producer: read the document info dictionary (Producer)
# --------------------------------------------------------------------------
python3 - <<PY
import json, sys
print(json.dumps({
  "file": sys.argv[1],                 # absolute or relative path to this PDF
  "size_mb": float("$SIZE_MB"),        # computed above
  "pages": int("$PAGES"),              # computed above (fallback 0)
  "image_page_ratio": 0.0,             # TODO: implement detection
  "dpi_estimate": 300,                 # TODO: derive from image streams if needed
  "avg_image_size_kb": 0.0,            # TODO: compute across image XObjects
  "fonts_embedded_pct": 0.5,           # TODO: inspect font resources
  "xref_error_count": 0,               # TODO: simple xref validation pass
  "ocr_required": 0,                   # 0 or 1; set 1 if text extraction fails heuristically
  "producer": "Unknown"                # TODO: read document info: Producer
}))
PY > "$OUT"
