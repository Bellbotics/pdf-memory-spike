"""
FastAPI sidecar for predicting **peak memory (MB)** required to process PDFs.

This service accepts a fixed set of lightweight PDF features and returns:
- `predicted_peak_mb`: model-estimated peak RSS (in MB)
- `decision`: one of {"ROUTE_BIG_MEMORY","STANDARD_PATH"} based on a threshold
- `threshold_mb`: the threshold used for the decision (request-provided or default)
- optionally `model_hash`: short SHA-256 digest of the loaded pipeline (if enabled)

# Why a sidecar?
Keeping the ML scoring in a separate, tiny process allows:
- Independent model releases / rollbacks without redeploying the main app
- Polyglot ML stacks (Python, scikit-learn) while the main service can be Java
- Resource isolation and simpler debugging / provenance logging

# Environment Variables
- PIPELINE_PATH (str, optional): absolute or relative path to `pipeline.pkl`.
  Defaults to `sidecar/models/pipeline.pkl`.
- DEFAULT_THRESHOLD_MB (float, optional): default decision threshold (MB).
  Defaults to 3500.0.
- INCLUDE_MODEL_HASH (bool-ish, optional): include a short model hash in responses
  for traceability. Accepts "1", "true", "yes" (case-insensitive). Defaults to false.
- LOG_LEVEL (str, optional): logging level (e.g., "DEBUG", "INFO"). Defaults to "INFO".

# Endpoints
- GET  /health  : readiness probe + model provenance snippet
- GET  /healthz : legacy liveness probe (always returns {"status":"ok"})
- POST /predict : score a single feature set (JSON body), return prediction & decision

Example request body for /predict:
{
  "features": {
    "size_mb": 2.1,
    "pages": 3,
    "image_page_ratio": 0.0,
    "dpi_estimate": 150,
    "avg_image_size_kb": 0.0,
    "fonts_embedded_pct": 1.0,
    "xref_error_count": 0,
    "ocr_required": 0,
    "producer": "UnitTest"
  },
  "big_mem_threshold_mb": 3500.0
}
"""

from __future__ import annotations

import hashlib
import logging
import os
import pickle
from pathlib import Path
from typing import Any, Iterable, Optional

import pandas as pd
from fastapi import FastAPI
from pydantic import BaseModel, Field

# ------------------------------------------------------------------------------
# Config
# ------------------------------------------------------------------------------

# Default on-disk pipeline location: sidecar/models/pipeline.pkl (repo-relative)
DEFAULT_LOCAL_MODEL = Path(__file__).parent / "models" / "pipeline.pkl"

# Path to the pickled scikit-learn Pipeline (or compatible estimator).
# Override via env: PIPELINE_PATH=/absolute/path/to/pipeline.pkl
PIPELINE_PATH = os.getenv("PIPELINE_PATH", str(DEFAULT_LOCAL_MODEL))

# Default decision threshold (MB). If the request body does not provide
# `big_mem_threshold_mb`, we will use this value.
DEFAULT_THRESHOLD_MB = float(os.getenv("DEFAULT_THRESHOLD_MB", "3500.0"))

# When true, responses will include a short model hash for traceability
# (useful for A/B tests, incident forensics, reproducibility).
INCLUDE_MODEL_HASH = os.getenv("INCLUDE_MODEL_HASH", "false").lower() in {"1", "true", "yes"}

# Basic logger. In k8s, prefer structured logging via sidecar/collector if needed.
logger = logging.getLogger("sidecar")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

# ------------------------------------------------------------------------------
# Model load + provenance helpers
# ------------------------------------------------------------------------------

def _compute_sha256(path: str) -> Optional[str]:
    """
    Return the SHA-256 hex digest of the file at `path`, or None if it does not exist.

    We compute this once at startup to provide a stable identifier for the loaded model
    over the life of the process. The full digest can be verbose in logs; we print a
    short prefix by default and optionally return the full value via /health.
    """
    p = Path(path)
    if not p.exists() or not p.is_file():
        return None
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def _file_size_bytes(path: str) -> Optional[int]:
    """
    Return the size of the file at `path` in bytes, or None if not found.
    Useful for quick sanity checks in logs (detecting empty/corrupt artifacts).
    """
    p = Path(path)
    try:
        return p.stat().st_size
    except FileNotFoundError:
        return None

def _load_pipe(path: str):
    """
    Load the pickled scikit-learn Pipeline (or compatible estimator) from disk.

    Raises:
        FileNotFoundError: if the model file is missing, with a helpful message
                           guiding the operator to train or set PIPELINE_PATH.
    """
    try:
        with open(path, "rb") as f:
            return pickle.load(f)
    except FileNotFoundError as e:
        raise FileNotFoundError(
            f"Model not found at '{path}'. "
            "Train to sidecar/models/ or set PIPELINE_PATH to an absolute file path."
        ) from e

def _extract_feature_names(model: Any) -> Optional[Iterable[str]]:
    """
    Best-effort extraction of feature names for provenance logs.

    We try common scikit-learn attributes exposed by modern estimators/pipelines:
    - `feature_names_in_` on the pipeline or the final estimator (sklearn >= 1.0)
    - For pipelines using `ColumnTransformer`, attempt `get_feature_names_out()`
      from later steps to surface the expanded feature list.
    Returns:
        A list of feature names, or None if unavailable.
    """
    # sklearn >=1.0 estimators often expose feature_names_in_
    names = getattr(model, "feature_names_in_", None)
    if names is not None:
        return list(names)

    # sklearn.pipeline.Pipeline: check final step
    step = getattr(model, "steps", None)
    if step and isinstance(step, list) and len(step) > 0:
        last_est = step[-1][1]
        names = getattr(last_est, "feature_names_in_", None)
        if names is not None:
            return list(names)

        # Sometimes a ColumnTransformer is used earlier; try to surface its output names
        for _, est in reversed(step):
            # ColumnTransformer.get_feature_names_out() (sklearn >=1.0)
            get_out = getattr(est, "get_feature_names_out", None)
            if callable(get_out):
                try:
                    return list(get_out())
                except Exception:
                    # Non-fatal: some estimators require a fitted state or throw
                    # on this call; we simply ignore.
                    pass

    # Fallback: none available
    return None

# Load model + compute provenance ONCE at startup.
# This avoids paying the I/O cost (and error surface) for each request.
MODEL_HASH = _compute_sha256(PIPELINE_PATH)
MODEL_BYTES = _file_size_bytes(PIPELINE_PATH)
pipe = _load_pipe(PIPELINE_PATH)  # raises FileNotFoundError with helpful message
MODEL_CLASS = pipe.__class__.__name__
MODEL_FEATURES = _extract_feature_names(pipe)

# ------------------------------------------------------------------------------
# Request models (Pydantic)
# ------------------------------------------------------------------------------

class PdfFeatures(BaseModel):
    """
    Lightweight, *model-ready* features describing a PDF.

    Notes on scaling/encoding conventions:
    - `fonts_embedded_pct`: normalized fraction in [0,1] (1.0 == 100% embedded).
    - `image_page_ratio`: fraction in [0,1] of pages that are primarily images.
    - `ocr_required`: integer 0/1 to avoid float round-trip ambiguity.
    - `producer`: free-form string (may be used by vectorizers if the pipeline expects it).
    """
    size_mb: float
    pages: int
    image_page_ratio: float = Field(ge=0, le=1)
    dpi_estimate: int
    avg_image_size_kb: float
    fonts_embedded_pct: float = Field(ge=0, le=1)
    xref_error_count: int
    ocr_required: int = Field(ge=0, le=1)
    producer: str

class ScoreRequest(BaseModel):
    """
    Envelope required by the service:

    Attributes:
        features: PdfFeatures — the actual feature payload.
        big_mem_threshold_mb: Optional[float] — overrides the default decision threshold.
                             If omitted, `DEFAULT_THRESHOLD_MB` is used.
    """
    features: PdfFeatures
    big_mem_threshold_mb: float | None = None

# ------------------------------------------------------------------------------
# FastAPI app
# ------------------------------------------------------------------------------

app = FastAPI(
    title="PDF Memory Spike Scorer (Sidecar)",
    version="1.0.0",
    description=(
        "Predict peak memory (MB) for PDF processing from lightweight features. "
        "Designed to be co-located as a sidecar with a primary application."
    ),
)

@app.on_event("startup")
def _log_provenance() -> None:
    """
    Emit a concise provenance line at startup so operators can quickly answer:
    - Which file was loaded?
    - How big was it?
    - Which model class is active?
    - What hash identifies this exact artifact?
    - (Preview) What are the feature names the model expects?
    """
    logger.info(
        "Loaded model: path=%s size_bytes=%s sha256=%s class=%s",
        str(PIPELINE_PATH),
        MODEL_BYTES,
        (MODEL_HASH[:12] if MODEL_HASH else None),
        MODEL_CLASS,
    )
    if MODEL_FEATURES:
        try:
            # Truncate long lists to keep logs readable
            preview = list(MODEL_FEATURES)[:32]
            logger.info("Model feature names (preview up to 32): %s", preview)
        except Exception:
            # Non-fatal; purely for logging
            pass

# ------------------------------------------------------------------------------
# Endpoints
# ------------------------------------------------------------------------------

@app.get("/health")
def health():
    """
    Readiness/health probe with a hint of model provenance.

    Returns:
        dict: { "status": "ok", "model_loaded": bool, "model_hash": Optional[str] }
    """
    exists = Path(PIPELINE_PATH).exists()
    return {
        "status": "ok",
        "model_loaded": exists,
        "model_hash": MODEL_HASH if exists else None,
    }

@app.get("/healthz")
def healthz():
    """
    Legacy liveness endpoint. Always returns 200/{"status":"ok"}.
    Keep this separate from `/health` for compatibility with simple probes.
    """
    return {"status": "ok"}

@app.post("/predict")
def predict(req: ScoreRequest):
    """
    Score a single PDF feature set using the pre-loaded pipeline.

    The input features are first wrapped in a single-row `pandas.DataFrame`
    to match common scikit-learn pipeline expectations (2D array-like).

    Decision logic:
      decision = "ROUTE_BIG_MEMORY" if predicted_peak_mb >= threshold else "STANDARD_PATH"

    Threshold precedence:
      1) `req.big_mem_threshold_mb` if provided
      2) `DEFAULT_THRESHOLD_MB` from environment

    Returns:
        dict: {
          "predicted_peak_mb": float,
          "decision": "ROUTE_BIG_MEMORY" | "STANDARD_PATH",
          "threshold_mb": float,
          ["model_hash"]: str  # only if INCLUDE_MODEL_HASH=True and model hash is available
        }

    Example:
        >>> predict(ScoreRequest(features=PdfFeatures(...)))
        {"predicted_peak_mb": 123.4, "decision": "STANDARD_PATH", "threshold_mb": 3500.0}
    """
    # Convert validated Pydantic model -> DataFrame (one row)
    df = pd.DataFrame([req.features.model_dump()])

    # The scikit-learn pipeline must implement .predict(X) -> array-like
    # where X is shape (n_samples, n_features)
    y = float(pipe.predict(df)[0])

    # Decide which path to route based on the threshold (request > env default)
    thr = req.big_mem_threshold_mb or DEFAULT_THRESHOLD_MB
    decision = "ROUTE_BIG_MEMORY" if y >= thr else "STANDARD_PATH"

    # Build response (optionally include model hash for traceability)
    resp = {
        "predicted_peak_mb": y,
        "decision": decision,
        "threshold_mb": thr,
    }
    if INCLUDE_MODEL_HASH and MODEL_HASH:
        resp["model_hash"] = MODEL_HASH
    return resp
