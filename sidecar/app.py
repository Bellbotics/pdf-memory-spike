# sidecar/app.py
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
DEFAULT_LOCAL_MODEL = Path(__file__).parent / "models" / "pipeline.pkl"
PIPELINE_PATH = os.getenv("PIPELINE_PATH", str(DEFAULT_LOCAL_MODEL))
DEFAULT_THRESHOLD_MB = float(os.getenv("DEFAULT_THRESHOLD_MB", "3500.0"))
INCLUDE_MODEL_HASH = os.getenv("INCLUDE_MODEL_HASH", "false").lower() in {"1", "true", "yes"}

logger = logging.getLogger("sidecar")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

# ------------------------------------------------------------------------------
# Model load + provenance helpers
# ------------------------------------------------------------------------------
def _compute_sha256(path: str) -> Optional[str]:
    p = Path(path)
    if not p.exists() or not p.is_file():
        return None
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def _file_size_bytes(path: str) -> Optional[int]:
    p = Path(path)
    try:
        return p.stat().st_size
    except FileNotFoundError:
        return None

def _load_pipe(path: str):
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
    Tries common scikit-learn attributes (on the pipeline or final estimator).
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
                    pass

    # Fallback: none available
    return None

# Load model + compute provenance
MODEL_HASH = _compute_sha256(PIPELINE_PATH)
MODEL_BYTES = _file_size_bytes(PIPELINE_PATH)
pipe = _load_pipe(PIPELINE_PATH)
MODEL_CLASS = pipe.__class__.__name__
MODEL_FEATURES = _extract_feature_names(pipe)

# ------------------------------------------------------------------------------
# Request models (Pydantic)
# ------------------------------------------------------------------------------
class PdfFeatures(BaseModel):
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
    features: PdfFeatures
    big_mem_threshold_mb: float | None = None

# ------------------------------------------------------------------------------
# FastAPI app
# ------------------------------------------------------------------------------
app = FastAPI()

@app.on_event("startup")
def _log_provenance() -> None:
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
            pass

# ------------------------------------------------------------------------------
# Endpoints
# ------------------------------------------------------------------------------
@app.get("/health")
def health():
    """
    Readiness/health probe with a hint of model provenance.
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
    Legacy liveness endpoint.
    """
    return {"status": "ok"}

@app.post("/predict")
def predict(req: ScoreRequest):
    """
    Score a PDF feature set using the pre-trained pipeline.
    """
    df = pd.DataFrame([req.features.model_dump()])
    y = float(pipe.predict(df)[0])

    thr = req.big_mem_threshold_mb or DEFAULT_THRESHOLD_MB
    decision = "ROUTE_BIG_MEMORY" if y >= thr else "STANDARD_PATH"

    resp = {
        "predicted_peak_mb": y,
        "decision": decision,
        "threshold_mb": thr,
    }
    if INCLUDE_MODEL_HASH and MODEL_HASH:
        resp["model_hash"] = MODEL_HASH
    return resp
