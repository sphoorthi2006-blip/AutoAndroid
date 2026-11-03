# Auto AI backend (Steps 10-16)

Does the audio listening for the Android app: **Silero VAD** (speech/silence) -> **Faster-Whisper** (what is really spoken) ->
caption-vs-audio diff -> **WhisperX / wav2vec2** forced alignment (exact word times). The app then puts every picture change on the
first spoken word of its scene.

## Run it (you provide the machine; nothing is hosted for you)
```bash
cd backend
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt            # whisperx pulls torch (large). Without it the server still works using Faster-Whisper's own word times (~0.1-0.2 s) and says so in the warnings.
sudo apt install ffmpeg
export AUTO_API_TOKEN="$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')"   # put this same token in the app
export AUTO_WHISPER_MODEL=large-v3         # default: large-v3 on GPU, small on CPU (first start downloads the models from HuggingFace)
uvicorn auto_backend.app:default_app --factory --host 0.0.0.0 --port 8000
```
The phone needs an **https://** address (put it behind Caddy/nginx/Cloudflare Tunnel/RunPod proxy); plain http is accepted by the app only for LAN addresses.
A GPU is strongly recommended: 10 min of narration ~ 1 min on a modern GPU, several minutes on CPU with `small`.

## API
`GET /v1/health` | `HEAD/PUT /v1/audio/{sha256}` | `POST /v1/jobs` | `GET /v1/jobs/{id}` - see `auto_backend/app.py`. Identical requests reuse one cached result; the same audio is never uploaded twice.

## Tests
`python3 -m pytest backend/tests` (17 tests; fake speech models - they check the logic, not recognition quality).
`scripts/run_parity.sh` additionally runs the Kotlin client against a live copy of this server.

## NOT done / limits
- Real Whisper/WhisperX/wav2vec2 weights were never run in the development sandbox (no access to HuggingFace). First real run happens on YOUR server: send the `warnings` and `stages` fields if anything looks wrong.
- Gemini script/image understanding (Steps 17-22) is not implemented.
- Upload is one PUT (retried), not chunk-resumable. Jobs are processed one at a time.
