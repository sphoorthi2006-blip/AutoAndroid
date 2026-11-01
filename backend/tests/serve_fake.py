"""Test server for the Kotlin client tests: REAL FastAPI app + REAL upload/job/auth code, FAKE speech models (this sandbox cannot download Whisper weights).
Usage: python serve_fake.py PORT DATA_DIR"""
import sys
import numpy as np, uvicorn
sys.path.insert(0, __file__.rsplit("/tests/", 1)[0])
from auto_backend import engine
from auto_backend.engine import Word
from auto_backend.app import create_app

class FakeASR:
    name = "fake-asr"
    def transcribe(self, x, language, prompt):
        if prompt == "FAIL": return [], "en"
        return [Word(t, 0.5 + i * 0.4, 0.8 + i * 0.4, 0.9) for i, t in enumerate("a young boy enters a dark forest".split())], "en"

engine.vad_probabilities = lambda x: np.ones(len(x) // 512, dtype=np.float32)
app = create_app(lambda: (FakeASR(), None), sys.argv[2], token="test-token")
uvicorn.run(app, host="127.0.0.1", port=int(sys.argv[1]), log_level="warning")
