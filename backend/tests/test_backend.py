import hashlib, subprocess, time
import numpy as np, pytest
from fastapi.testclient import TestClient
from auto_backend import engine
from auto_backend.engine import Word
from auto_backend.app import create_app


def wav(path, seconds=6.0, tone=True):
    src = f"sine=frequency=220:duration={seconds}" if tone else f"anullsrc=r=16000:cl=mono"
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", src] + ([] if tone else ["-t", str(seconds)]) + [str(path)]
    subprocess.run(cmd, check=True)
    return path


class FakeASR:
    name = "fake-asr"
    def __init__(self, words): self.words = words
    def transcribe(self, x, language, prompt): return list(self.words), "en"

class FakeAligner:
    name = "fake-aligner"
    def align(self, x, words, language): return [Word(w.word, w.start + 0.01, w.end + 0.01, 0.95) for w in words]

class BadAligner:
    name = "bad"
    def align(self, x, words, language): raise RuntimeError("model not found")

WORDS = [Word(t, 0.5 + i * 0.4, 0.8 + i * 0.4, 0.9) for i, t in enumerate("a young boy enters a dark forest".split())]
def fake_vad(x): return np.ones(len(x) // 512, dtype=np.float32)       # "speech everywhere"


# ---- pure functions
def test_segments_basic_and_min_lengths():
    p = np.array([0]*10 + [1]*30 + [0]*20 + [1]*30 + [0]*10 + [1]*3 + [0]*20, dtype=float)   # last blip is 3 frames (<0.25 s)
    segs = engine.probs_to_segments(p, pad=0)
    assert len(segs) == 2 and abs(segs[0][0] - 10 * 0.032) < 1e-6
    assert engine.silences_between(segs)[0][1] - engine.silences_between(segs)[0][0] == pytest.approx(0.64, abs=0.05)

def test_short_dip_inside_speech_is_not_a_pause():
    p = np.array([1]*20 + [0]*4 + [1]*20, dtype=float)   # 0.128 s dip < min_silence
    assert len(engine.probs_to_segments(p, pad=0)) == 1

def test_compare_tokens():
    d = engine.compare_tokens("the boy walks deeper into the forest now".split(), "the boy walks into the dark forest".split())
    assert d["ratio"] > 0.6
    assert any("dark" in m["words"] for m in d["missing_in_audio"]) and any("deeper" in m["words"] for m in d["extra_in_audio"])
    assert engine.compare_tokens(["a"], ["a"])["ratio"] == 1.0

def test_clamp_moves_word_out_of_silence_and_keeps_order():
    speech = [(0.0, 1.0), (3.0, 5.0)]
    out, moved = engine.clamp_to_speech([Word("a", 0.2, 0.5), Word("b", 2.5, 2.8), Word("c", 3.5, 3.9)], speech)
    assert moved == 1 and out[1].start == 3.0 and all(a.end <= b.start + 1e-9 for a, b in zip(out, out[1:]))
    assert engine.clamp_to_speech(WORDS, [])[1] == 0


# ---- analyze()
def test_analyze_full_path(tmp_path):
    a = wav(tmp_path / "a.wav")
    r = engine.analyze(str(a), FakeASR(WORDS), FakeAligner(), "A young boy enters a dark forest", "A young boy enters a dark forest", vad_probs_fn=fake_vad)
    assert r.alignment == "fake-aligner" and r.duration == pytest.approx(6.0, abs=0.1)
    w = r.segments[0]["words"]; assert len(w) == 7 and w[0]["start"] == pytest.approx(0.51, abs=1e-3)
    assert r.caption_diff["ratio"] == 1.0 and not r.warnings

def test_analyze_aligner_failure_is_reported_not_hidden(tmp_path):
    a = wav(tmp_path / "a.wav")
    r = engine.analyze(str(a), FakeASR(WORDS), BadAligner(), vad_probs_fn=fake_vad)
    assert r.alignment == "whisper" and r.stages["align"].startswith("failed") and any("alignment failed" in w for w in r.warnings)

def test_analyze_without_aligner_warns(tmp_path):
    r = engine.analyze(str(wav(tmp_path / "a.wav")), FakeASR(WORDS), None, vad_probs_fn=fake_vad)
    assert any("not installed" in w for w in r.warnings)

def test_caption_mismatch_warns_and_uses_audio(tmp_path):
    r = engine.analyze(str(wav(tmp_path / "a.wav")), FakeASR(WORDS), FakeAligner(), "", "completely different caption text about something else entirely", vad_probs_fn=fake_vad)
    assert any("differ a lot" in w for w in r.warnings) and r.caption_diff["ratio"] < 0.6

def test_real_silero_on_silence_finds_no_speech(tmp_path):
    r = engine.analyze(str(wav(tmp_path / "s.wav", 3.0, tone=False)), FakeASR(WORDS), FakeAligner())
    assert r.speech == [] and any("NO speech" in w for w in r.warnings) and r.stages["vad"] == "ok"

def test_vad_failure_does_not_kill_analysis(tmp_path):
    def boom(x): raise RuntimeError("onnx missing")
    r = engine.analyze(str(wav(tmp_path / "a.wav")), FakeASR(WORDS), FakeAligner(), vad_probs_fn=boom)
    assert r.stages["vad"].startswith("failed") and r.segments

def test_empty_asr_and_bad_audio_raise_clear_errors(tmp_path):
    with pytest.raises(RuntimeError, match="no words"): engine.analyze(str(wav(tmp_path / "a.wav")), FakeASR([]), None, vad_probs_fn=fake_vad)
    bad = tmp_path / "bad.wav"; bad.write_bytes(b"not audio")
    with pytest.raises(RuntimeError, match="could not be decoded"): engine.analyze(str(bad), FakeASR(WORDS), None)


# ---- HTTP API
@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(engine, "vad_probabilities", fake_vad)
    calls = {"n": 0}
    def models(): calls["n"] += 1; return FakeASR(WORDS), FakeAligner()
    app = create_app(models, str(tmp_path / "data"), token="secret-token")
    c = TestClient(app, headers={"Authorization": "Bearer secret-token"}); c.calls = calls; return c

def upload(c, path):
    b = path.read_bytes(); h = hashlib.sha256(b).hexdigest()
    return h, c.put(f"/v1/audio/{h}", content=b)

def wait(c, jid):
    for _ in range(100):
        d = c.get(f"/v1/jobs/{jid}").json()
        if d["status"] in ("done", "error"): return d
        time.sleep(0.05)
    raise AssertionError("job did not finish")

def test_auth_required_and_health_open(client):
    anon = TestClient(client.app)
    assert anon.get("/v1/health").status_code == 200
    assert anon.head("/v1/audio/" + "0" * 64).status_code == 401
    assert anon.post("/v1/jobs", json={}).status_code == 401
    assert anon.head("/v1/audio/" + "0" * 64, headers={"Authorization": "Bearer nope"}).status_code == 401

def test_server_refuses_to_start_without_token(tmp_path, monkeypatch):
    monkeypatch.delenv("AUTO_API_TOKEN", raising=False); monkeypatch.delenv("AUTO_ALLOW_NO_AUTH", raising=False)
    with pytest.raises(RuntimeError, match="AUTO_API_TOKEN"): create_app(lambda: None, str(tmp_path / "d"))

def test_upload_hash_check_and_dedupe(client, tmp_path):
    a = wav(tmp_path / "a.wav", 2.0)
    assert client.head("/v1/audio/" + hashlib.sha256(a.read_bytes()).hexdigest()).status_code == 404
    assert client.put("/v1/audio/" + "a" * 64, content=b"hello").status_code == 400
    h, r = upload(client, a); assert r.status_code == 200
    assert client.head(f"/v1/audio/{h}").status_code == 200
    assert client.put("/v1/audio/not-a-hash", content=b"x").status_code == 400

def test_job_lifecycle_and_reuse(client, tmp_path):
    h, _ = upload(client, wav(tmp_path / "a.wav"))
    body = {"audio_sha256": h, "script_text": "a young boy enters a dark forest", "caption_text": "a young boy enters a dark forest"}
    j = client.post("/v1/jobs", json=body).json(); d = wait(client, j["job_id"])
    assert d["status"] == "done" and d["result"]["segments"][0]["words"][0]["word"] == "a"
    j2 = client.post("/v1/jobs", json=body).json()
    assert j2["job_id"] == j["job_id"] and j2["reused"] and client.calls["n"] == 1   # models loaded once, no reprocessing
    assert client.post("/v1/jobs", json={**body, "caption_text": "different"}).json()["job_id"] != j["job_id"]

def test_job_for_unknown_audio_and_unknown_job(client):
    assert client.post("/v1/jobs", json={"audio_sha256": "b" * 64}).status_code == 409
    assert client.get("/v1/jobs/" + "c" * 32).status_code == 404

def test_job_error_is_reported(tmp_path):
    def models(): return FakeASR([]), None
    c = TestClient(create_app(models, str(tmp_path / "d"), token="t"), headers={"Authorization": "Bearer t"})
    h, _ = upload(c, wav(tmp_path / "a.wav", 2.0))
    d = wait(c, c.post("/v1/jobs", json={"audio_sha256": h}).json()["job_id"])
    assert d["status"] == "error" and "no words" in d["error"]
