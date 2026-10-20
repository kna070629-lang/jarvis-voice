#!/usr/bin/env python3
"""Transcribe a Telegram voice note (by file_id) or local audio file via whisper.cpp.

Usage:
  transcribe_voice.py <telegram_file_id>
  transcribe_voice.py --local /path/to/audio.ogg
  transcribe_voice.py --bot-token-get <file_id>   # just download, don't transcribe
  transcribe_voice.py --test /path/to/audio.wav   # transcribe a local file
"""
import argparse, json, os, subprocess, sys, tempfile, urllib.request, urllib.parse

# --- config ---
DEFAULT_BOT_TOKEN = "8657785541:AAFyMJTKPKNq2wknUxHD9A0RD1cNFb1_uE8"
WHISPER_BIN = "/data/data/com.termux/files/home/whisper.cpp/build/bin/whisper-cli"
DEFAULT_MODEL = "/data/data/com.termux/files/home/whisper.cpp/models/ggml-base.en.bin"


def get_telegram_file(file_id, bot_token=DEFAULT_BOT_TOKEN):
    """Download a Telegram file via Bot API, return local path."""
    api_url = f"https://api.telegram.org/bot{bot_token}/getFile"
    qs = urllib.parse.urlencode({"file_id": file_id})
    req_url = f"{api_url}?{qs}"
    with urllib.request.urlopen(req_url, timeout=30) as r:
        data = json.loads(r.read())
    if not data.get("ok"):
        raise RuntimeError(f"getFile failed: {data}")
    file_path = data["result"]["file_path"]  # e.g. "voice/file_42.ogg"
    download_url = f"https://api.telegram.org/file/bot{bot_token}/{file_path}"
    suffix = "." + file_path.split(".")[-1]
    fd, local = tempfile.mkstemp(prefix="telegram_", suffix=suffix)
    os.close(fd)
    with urllib.request.urlopen(download_url, timeout=120) as r, open(local, "wb") as f:
        f.write(r.read())
    return local, file_path


def ffmpeg_to_wav(in_path, out_path):
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error",
         "-i", in_path, "-ar", "16000", "-ac", "1",
         "-f", "wav", out_path],
        check=True, capture_output=True,
    )


def whisper_transcribe(wav_path, model=DEFAULT_MODEL, language="en"):
    cmd = [
        WHISPER_BIN, "-m", model, "-f", wav_path,
        "--no-timestamps", "--no-prints",
        "-l", language,
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    if r.returncode != 0:
        sys.stderr.write(f"whisper-cli stderr: {r.stderr}\n")
        raise RuntimeError(f"whisper-cli failed (rc={r.returncode})")
    return r.stdout.strip()


def transcribe_file(path, model=DEFAULT_MODEL, language="en"):
    fd, wav_path = tempfile.mkstemp(prefix="stt_", suffix=".wav")
    os.close(fd)
    try:
        ffmpeg_to_wav(path, wav_path)
        return whisper_transcribe(wav_path, model=model, language=language)
    finally:
        try:
            os.unlink(wav_path)
        except OSError:
            pass


def main():
    p = argparse.ArgumentParser()
    p.add_argument("file_id", nargs="?", help="telegram file_id")
    p.add_argument("--local", help="local audio file path")
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--language", "-l", default="en")
    p.add_argument("--bot-token", default=DEFAULT_BOT_TOKEN)
    args = p.parse_args()

    if args.local:
        text = transcribe_file(args.local, model=args.model, language=args.language)
        print(text)
        return

    if not args.file_id:
        sys.stderr.write("need a file_id or --local\n")
        sys.exit(2)

    local, fp = get_telegram_file(args.file_id, bot_token=args.bot_token)
    try:
        text = transcribe_file(local, model=args.model, language=args.language)
    finally:
        try:
            os.unlink(local)
        except OSError:
            pass
    print(f"[{fp}]")
    print(text)


if __name__ == "__main__":
    main()
