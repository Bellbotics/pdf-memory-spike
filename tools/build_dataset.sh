#!/usr/bin/env bash
set -euo pipefail
IN_DIR="${1:-./samples}"
OUT_CSV="${2:-training/sample_data_real.csv}"

TMP="$(mktemp -d)"
for f in "$IN_DIR"/*.pdf; do
  [ -e "$f" ] || continue
  python3 tools/pdf_features_py.py "$f" > "$TMP/$(basename "$f").jsonl"
done

python3 - <<PY
import json,glob,csv,sys,os
tmp = sys.argv[1]; out=sys.argv[2]
rows = []
for p in glob.glob(os.path.join(tmp,"*.jsonl")):
    rows.append(json.load(open(p)))
# set a dummy target for now (you'll replace with real observed peaks later)
for r in rows: r['peak_mem_mb'] = 1000.0
with open(out,"w",newline="") as f:
    w=csv.DictWriter(f,fieldnames=rows[0].keys())
    w.writeheader(); w.writerows(rows)
print("wrote",out,"rows:",len(rows))
PY "$TMP" "$OUT_CSV"
