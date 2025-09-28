# Spring WebFlux Service

Reactive service that accepts PDF uploads, extracts features (PDFBox), predicts memory spikes (sidecar or local model), measures actual peak memory, and (optionally) trains a tiny in-process model.

## Endpoints

- POST /v1/upload/pdf — multipart/form-data
  - file: PDF (application/pdf) — capped by bds.max-bytes (default 50 MiB)
  - train: optional (true|on|false) — default on
- POST /v1/intake/route — JSON features (no file upload)
- GET /v1/model — { beta[], samples, threshold_mb } snapshot

Actuator (/actuator/**)
- health, metrics, prometheus (always)
- info, env (local profile)

## Run (local)

```bash
./mvnw -q spring-boot:run   -Dspring-boot.run.profiles=local   -Dspring-boot.run.arguments="--triage.base-url=http://127.0.0.1:8000 --bds.retrain-every=1"
```

UI at http://127.0.0.1:8033/

## Observability

- bds.pdf.extract.duration — feature extraction timer
- bds.sidecar.predict.duration — sidecar call timer
- bds.upload.bytes — upload size summary
- bds.route.decision{decision,source} — counter

## Config keys used

- triage.base-url (required)
- bds.max-bytes, bds.data-dir, bds.train-csv, bds.model-file
- bds.retrain-every, bds.route-threshold-mb
