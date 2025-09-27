#!/usr/bin/env bash
set -euo pipefail
PDF="$1"
OUT="$2"

# Requires pdfbox-app.jar (2.0.x). Put it at tools/pdfbox-app.jar
JAR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$JAR_DIR/pdfbox-app.jar"

# pages
PAGES=$(java -jar "$JAR" PDFDebugger "$PDF" 2>/dev/null | grep -m1 "Number of pages:" | awk '{print $4}')
PAGES=${PAGES:-0}

# crude heuristics; you can refine with custom parser:
SIZE_MB=$(python3 - <<PY
import os,sys; print(round(os.path.getsize(sys.argv[1]) / (1024*1024),3))
PY
"$PDF")

# minimal JSON line (extend as you wish)
python3 - <<PY
import json,sys
print(json.dumps({
  "file": sys.argv[1],
  "size_mb": float("$SIZE_MB"),
  "pages": int("$PAGES"),
  "image_page_ratio": 0.0,
  "dpi_estimate": 300,
  "avg_image_size_kb": 0.0,
  "fonts_embedded_pct": 0.5,
  "xref_error_count": 0,
  "ocr_required": 0,
  "producer": "Unknown"
}))
PY > "$OUT"
