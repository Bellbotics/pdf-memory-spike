#!/usr/bin/env bash
# Robust dataset builder:
# - Validates dependencies (python3, tools/pdf_features_py.py)
# - Accepts IN_DIR and OUT_CSV args
# - Handles no-PDF case cleanly
# - Cleans up temp files, supports .pdf and .PDF
# - Creates OUT_CSV parent directory automatically

set -euo pipefail

usage() {
  cat <<EOF
Usage: $(basename "$0") [IN_DIR] [OUT_CSV]

Build a CSV of PDF features using tools/pdf_features_py.py.

Args:
  IN_DIR   Directory containing *.pdf files (default: ./samples)
  OUT_CSV  Output CSV path (default: training/sample_data_real.csv)

Example:
  $(basename "$0") ./samples training/sample_data_real.csv
EOF
}

IN_DIR="${1:-./samples}"
OUT_CSV="${2:-training/sample_data_real.csv}"

# Basic dependency checks
command -v python3 >/dev/null || { echo "python3 not found"; exit 1; }
[ -f tools/pdf_features_py.py ] || { echo "missing tools/pdf_features_py.py"; exit 1; }

# Ensure output directory exists
mkdir -p "$(dirname "$OUT_CSV")"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

shopt -s nullglob
pdfs=( "$IN_DIR"/*.pdf "$IN_DIR"/*.PDF )
if (( ${#pdfs[@]} == 0 )); then
  : > "$OUT_CSV"   # create empty CSV
  echo "No PDFs found in '$IN_DIR'. Wrote empty file: $OUT_CSV"
  exit 0
fi

echo "Extracting features from ${#pdfs[@]} PDFs in '$IN_DIR'..."
for f in "${pdfs[@]}"; do
  base="$(basename "$f")"
  # Expect a single JSON object per file from the extractor
  python3 tools/pdf_features_py.py "$f" > "$TMP/${base%.*}.json"
done

# Merge JSON rows -> CSV with a dummy target column
python3 - <<'PY' "$TMP" "$OUT_CSV"
import json,glob,csv,sys,os
tmp, out = sys.argv[1], sys.argv[2]

rows = []
for p in glob.glob(os.path.join(tmp,"*.json")):
    with open(p,"r") as fh:
        rows.append(json.load(fh))

# Add placeholder target for training scaffolding; replace later with real peaks.
for r in rows:
    r.setdefault('peak_mem_mb', 1000.0)

os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
if not rows:
    open(out,"w").close()
    print("No rows assembled; wrote empty file:", out)
    raise SystemExit(0)

# Use a stable header: union of keys across rows, sorted, with 'peak_mem_mb' last if present.
all_keys = set().union(*[r.keys() for r in rows])
if 'peak_mem_mb' in all_keys:
    all_keys.remove('peak_mem_mb')
    fieldnames = sorted(all_keys) + ['peak_mem_mb']
else:
    fieldnames = sorted(all_keys)

with open(out, "w", newline="") as f:
    w = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
    w.writeheader()
    for r in rows:
        # Ensure all fields exist (missing -> empty)
        w.writerow({k: r.get(k, "") for k in fieldnames})

print(f"wrote {out} rows: {len(rows)}; columns: {len(fieldnames)}")
PY

echo "Done: $OUT_CSV"
