"""HTTP API for the Auto Android app (Step 10).
 GET  /v1/health                         (no auth)
 HEAD /v1/audio/{sha256}                 200 if this exact file is already on the server (so it is never uploaded twice)
 PUT  /v1/audio/{sha256}                 raw bytes; the server re-hashes and rejects a mismatch
 POST /v1/jobs   {audio_sha256, script_text?, caption_text?, language?} -> {job_id}; identical requests share one job/result
 GET  /v1/jobs/{id}                      {status: queued|running|done|error, stage, progress, result?, error?}
Auth: `Authorization: Bearer <AUTO_API_TOKEN>`. The server refuses to start without a token unless AUTO_ALLOW_NO_AUTH=1 (local testing only).
"""
from __future__ import annotations
import hashlib, hmac, json, os, re, tempfile, threading, traceback
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from fastapi import Depends, FastAPI, HTTPException, Request, Response
from . import engine

HASH_RE = re.compile(r"^[0-9a-f]{64}$")
MAX_UPLOAD = int(os.environ.get("AUTO_MAX_UPLOAD_MB", "1024")) * 1024 * 1024
ENGINE_VERSION = f"auto-engine-{engine.RESULT_VERSION}"


def create_app(make_models, data_dir: str | None = None, token: str | None = None) -> FastAPI:
    """make_models() -> (asr, aligner_or_None); called lazily on the first job so the server starts fast."""
    token = token if token is not None else os.environ.get("AUTO_API_TOKEN", "")
    if not token and os.environ.get("AUTO_ALLOW_NO_AUTH") != "1":
        raise RuntimeError("Set AUTO_API_TOKEN (a long random string) before starting the server.")
    root = Path(data_dir or os.environ.get("AUTO_DATA_DIR", "./auto_data")); (root / "audio").mkdir(parents=True, exist_ok=True); (root / "jobs").mkdir(exist_ok=True)
    app = FastAPI(title="Auto backend")
    pool, lock, models, mem = ThreadPoolExecutor(max_workers=1), threading.Lock(), {}, {}

    def auth(request: Request):
        if not token:
            return
        got = request.headers.get("authorization", "")
        if not hmac.compare_digest(got.encode(), f"Bearer {token}".encode()):
            raise HTTPException(401, "Missing or wrong API token.")

    def apath(h: str) -> Path:
        if not HASH_RE.match(h): raise HTTPException(400, "Bad audio hash (expected 64 lowercase hex characters).")
        return root / "audio" / h

    @app.get("/v1/health")
    def health(): return {"ok": True, "engine": ENGINE_VERSION}

    @app.head("/v1/audio/{h}", dependencies=[Depends(auth)])
    def has_audio(h: str): return Response(status_code=200 if apath(h).is_file() else 404)

    @app.put("/v1/audio/{h}", dependencies=[Depends(auth)])
    async def put_audio(h: str, request: Request):
        dest = apath(h); md, n = hashlib.sha256(), 0
        fd, tmp = tempfile.mkstemp(dir=root / "audio", suffix=".part")
        try:
            with os.fdopen(fd, "wb") as f:
                async for chunk in request.stream():
                    n += len(chunk)
                    if n > MAX_UPLOAD: raise HTTPException(413, f"File larger than {MAX_UPLOAD // 2**20} MB.")
                    md.update(chunk); f.write(chunk)
            if md.hexdigest() != h: raise HTTPException(400, "Upload corrupted: hash does not match. Send it again.")
            os.replace(tmp, dest)
        finally:
            if os.path.exists(tmp): os.unlink(tmp)
        return {"stored": h, "bytes": n}

    def job_file(jid): return root / "jobs" / f"{jid}.json"

    def save(jid, doc): 
        tmp = job_file(jid).with_suffix(".tmp"); tmp.write_text(json.dumps(doc)); os.replace(tmp, job_file(jid)); mem[jid] = doc

    def run(jid, body):
        def prog(stage, frac): mem[jid] = {**mem[jid], "status": "running", "stage": stage, "progress": frac}
        try:
            mem[jid] = {**mem[jid], "status": "running"}
            with lock:
                if "m" not in models: models["m"] = make_models()
            asr, aligner = models["m"]
            res = engine.analyze(str(apath(body["audio_sha256"])), asr, aligner, body.get("script_text", ""), body.get("caption_text", ""), body.get("language"), prog)
            save(jid, {"job_id": jid, "status": "done", "stage": "done", "progress": 1.0, "result": res.to_dict()})
        except Exception as e:
            traceback.print_exc()
            save(jid, {"job_id": jid, "status": "error", "stage": mem[jid].get("stage", ""), "progress": 0, "error": str(e)[:500]})

    @app.post("/v1/jobs", dependencies=[Depends(auth)])
    async def submit(request: Request):
        body = await request.json()
        h = body.get("audio_sha256", ""); apath(h)
        if not apath(h).is_file(): raise HTTPException(409, "Audio not uploaded yet (PUT /v1/audio/{sha256} first).")
        key = json.dumps([ENGINE_VERSION, h, body.get("script_text", ""), body.get("caption_text", ""), body.get("language")], sort_keys=True)
        jid = hashlib.sha256(key.encode()).hexdigest()[:32]
        if jid in mem and mem[jid]["status"] in ("queued", "running", "done"): return {"job_id": jid, "reused": True}
        if job_file(jid).is_file():
            doc = json.loads(job_file(jid).read_text())
            if doc.get("status") == "done": mem[jid] = doc; return {"job_id": jid, "reused": True}
        mem[jid] = {"job_id": jid, "status": "queued", "stage": "queued", "progress": 0.0}
        pool.submit(run, jid, body)
        return {"job_id": jid, "reused": False}

    @app.get("/v1/jobs/{jid}", dependencies=[Depends(auth)])
    def status(jid: str):
        if not re.match(r"^[0-9a-f]{32}$", jid): raise HTTPException(400, "Bad job id.")
        if jid in mem: return mem[jid]
        if job_file(jid).is_file(): mem[jid] = json.loads(job_file(jid).read_text()); return mem[jid]
        raise HTTPException(404, "Unknown job (the server may have been reset; submit again).")

    return app


def default_app():
    """uvicorn auto_backend.app:default_app --factory"""
    def make():
        import torch  # noqa: only to pick the device
        dev = "cuda" if torch.cuda.is_available() else "cpu"
        size = os.environ.get("AUTO_WHISPER_MODEL", "large-v3" if dev == "cuda" else "small")
        asr = engine.FasterWhisperASR(size, device=dev, compute_type="float16" if dev == "cuda" else "int8")
        try: aligner = engine.WhisperXAligner(dev)
        except Exception as e: print("WhisperX unavailable:", e); aligner = None
        return asr, aligner
    def make_safe():
        try: return make()
        except ImportError:   # no torch -> CPU faster-whisper only
            asr = engine.FasterWhisperASR(os.environ.get("AUTO_WHISPER_MODEL", "small"), device="cpu", compute_type="int8")
            return asr, None
    return create_app(make_safe)
