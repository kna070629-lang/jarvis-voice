"""jarvis_voice.py — The community JARVIS voice, spoken with Piper.

The voice is a community trained model that emulates the way JARVIS speaks in
the films. It is not the actual film voice and it is not a Marvel asset. The
model is published under the MIT licence by jgkawell on Hugging Face, so it can
be redistributed.

The synthesis runs entirely on the phone, so the assistant can speak without a
network once the model has been downloaded. The model itself is large, so it
is loaded once and kept warm.

The heavy lifting is done by onnxruntime with espeak-ng doing the
phonemisation, which is the same arrangement the Piper runtime uses.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import socket
import struct
import subprocess
import threading
import wave
from pathlib import Path


log = logging.getLogger("jarvis.voice")

MODEL_DIR = Path(os.environ.get(
    "JARVIS_VOICE_DIR",
    str(Path.home() / ".local" / "share" / "jarvis" / "voices" / "jarvis-high"),
))

# The espeak-ng data and the onnxruntime that Piper uses are in the container
# that runs beside the backend.
ESPEAK_BIN = os.environ.get("JARVIS_ESPEAK_BIN", "espeak-ng")
ONNXRUNTIME_PYTHON = os.environ.get("JARVIS_ONNX_PYTHON", "/usr/bin/python3")


class VoiceUnavailable(RuntimeError):
    """The voice model or the engine is not ready."""


def model_files() -> tuple[Path, Path]:
    """The model and its configuration."""
    onnx = MODEL_DIR / "jarvis-high.onnx"
    config = MODEL_DIR / "jarvis-high.onnx.json"
    return onnx, config


def is_installed() -> bool:
    """True when the model has been downloaded and is complete."""
    onnx, config = model_files()
    return onnx.is_file() and config.is_file() and onnx.stat().st_size > 1_000_000


def install_status() -> dict:
    """What the diagnostics screen shows about the voice."""
    onnx, config = model_files()
    return {
        "installed": is_installed(),
        "directory": str(MODEL_DIR),
        "model_bytes": onnx.stat().st_size if onnx.is_file() else 0,
        "config_present": config.is_file(),
        "engine": "piper (onnxruntime + espeak-ng)",
        "espeak": shutil.which(ESPEAK_BIN) is not None,
    }


class JarvisVoice:
    """Synthesises speech with the community JARVIS voice."""

    def __init__(self, model_dir: Path | None = None):
        self.model_dir = Path(model_dir) if model_dir else MODEL_DIR
        self._session = None
        self._config: dict | None = None
        self._lock = threading.Lock()

    # -- the model --------------------------------------------------------
    def _load(self):
        """Load the model once and keep it warm."""
        if self._session is not None:
            return
        with self._lock:
            if self._session is not None:
                return
            onnx = self.model_dir / "jarvis-high.onnx"
            config = self.model_dir / "jarvis-high.onnx.json"
            if not onnx.is_file() or not config.is_file():
                raise VoiceUnavailable("The JARVIS voice model has not been downloaded.")
            try:
                import onnxruntime  # type: ignore
            except ImportError as exc:
                raise VoiceUnavailable("The speech engine is not installed.") from exc
            try:
                self._config = json.loads(config.read_text(encoding="utf-8"))
                options = onnxruntime.SessionOptions()
                options.intra_op_num_threads = 2
                options.inter_op_num_threads = 1
                options.log_severity_level = 3
                self._session = onnxruntime.InferenceSession(
                    str(onnx),
                    sess_options=options,
                    providers=["CPUExecutionProvider"],
                )
            except Exception as exc:
                raise VoiceUnavailable(f"The voice model could not be loaded: {exc}") from exc
            log.info("The JARVIS voice model is loaded")

    def warm(self) -> bool:
        """Load the model ahead of time."""
        try:
            self._load()
            return True
        except VoiceUnavailable as exc:
            log.warning("The voice could not be warmed: %s", exc)
            return False

    # -- phonemes ---------------------------------------------------------
    def phonemize(self, text: str) -> list[str]:
        """Turn the text into the phonemes the model understands.

        The espeak-ng bridge writes the phonemes with an underscore between
        them, and a space between the words. The underscore becomes the word
        separator of the model.
        """
        voice = (self._config or {}).get("espeak", {}).get("voice", "en-gb-x-rp")
        try:
            result = subprocess.run(
                [ESPEAK_BIN, "-v", voice, "--ipa=1", "-q", text],
                capture_output=True,
                text=True,
                timeout=30,
            )
        except FileNotFoundError as exc:
            raise VoiceUnavailable("espeak-ng is not installed.") from exc
        except subprocess.TimeoutExpired as exc:
            raise VoiceUnavailable("The phonemiser did not answer in time.") from exc

        phonemes: list[str] = []
        for word in result.stdout.strip().split():
            # The space is the separator between the words. The phonemes inside
            # a word follow each other directly.
            if phonemes:
                phonemes.append(" ")
            for piece in word.split("_"):
                # Every phoneme of this model is a single character, so the
                # stress mark and the vowel are separate phonemes.
                for character in piece:
                    if character:
                        phonemes.append(character)
        return phonemes

    # -- synthesis --------------------------------------------------------
    def synthesize(self, text: str, length_scale: float = 1.0) -> bytes:
        """Synthesise the text and return a wave file."""
        self._load()
        config = self._config or {}
        id_map = config.get("phoneme_id_map") or {}
        if not id_map:
            raise VoiceUnavailable("The voice configuration has no phoneme map.")

        phonemes = self.phonemize(text)
        if not phonemes:
            raise VoiceUnavailable("The text produced no phonemes.")

        pad = id_map.get("_", [0])
        bos = id_map.get("^", [1])
        eos = id_map.get("$", [2])
        ids: list[int] = []
        ids.extend(bos)
        ids.extend(pad)
        for phoneme in phonemes:
            if phoneme not in id_map:
                continue
            ids.extend(id_map[phoneme])
            ids.extend(pad)
        ids.extend(eos)

        try:
            import numpy as np
        except ImportError as exc:
            raise VoiceUnavailable("The speech engine is missing its maths library.") from exc

        inference = config.get("inference") or {}
        noise_scale = float(inference.get("noise_scale", 0.667))
        model_length_scale = float(inference.get("length_scale", 1.15) or 1.15)
        noise_w = float(inference.get("noise_w", 0.8))

        input_ids = np.expand_dims(np.array(ids, dtype=np.int64), 0)
        input_lengths = np.array([input_ids.shape[1]], dtype=np.int64)
        scales = np.array([noise_scale, length_scale * model_length_scale, noise_w], dtype=np.float32)
        args = {
            "input": input_ids,
            "input_lengths": input_lengths,
            "scales": scales,
        }
        try:
            outputs = self._session.run(None, args)
        except Exception as exc:
            raise VoiceUnavailable(f"The voice model could not run: {exc}") from exc

        # This export has a single output that holds the audio as it should be
        # played, so it is scaled straight into the sixteen bit range. The
        # extra hop factor that some exports need would push it past the top
        # of the range and the speech would come out as noise.
        audio = outputs[-1]
        audio = np.asarray(audio, dtype=np.float32).squeeze()
        peak = float(np.abs(audio).max()) if audio.size else 0.0
        if peak > 0.0 and peak < 0.01:
            # A very quiet result means the export still wants the hop factor.
            audio = audio * (256.0 / max(peak, 1e-6)) * 0.2
        samples = np.clip(audio * 32767.0, -32768.0, 32767.0).astype(np.int16)

        rate = int((config.get("audio") or {}).get("sample_rate") or 22050)
        return wave_bytes(samples, rate)


class VoiceClient:
    """Talks to the voice server that runs beside the backend.

    The server holds the model warm, so a request only pays for the
    synthesis. The socket is used because the container that runs the engine
    has its own network namespace, but it shares the file system.
    """

    SOCKET_PATH = os.environ.get(
        "JARVIS_PIPER_SOCKET",
        "/data/data/com.termux/files/home/.local/share/jarvis/piper.sock",
    )

    def __init__(self, socket_path: str | None = None):
        self.socket_path = socket_path or self.SOCKET_PATH
        self._lock = threading.Lock()

    def available(self) -> bool:
        return Path(self.socket_path).exists()

    def synthesize(self, text: str, timeout: float = 120.0) -> bytes:
        """Speak the text and return a wave file."""
        with self._lock:
            connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            connection.settimeout(timeout)
            try:
                connection.connect(self.socket_path)
            except OSError as exc:
                raise VoiceUnavailable("The JARVIS voice server is not running.") from exc
            try:
                connection.sendall(text.encode("utf-8"))
                header = b""
                while b"\n" not in header:
                    piece = connection.recv(1)
                    if not piece:
                        raise VoiceUnavailable("The voice server closed the connection.")
                    header += piece
                marker, _, size = header.decode("ascii", "replace").partition(" ")
                if marker != "OK":
                    raise VoiceUnavailable(size.strip() or "the voice server reported an error")
                length = int(size.strip())
                data = bytearray()
                while len(data) < length:
                    piece = connection.recv(min(65536, length - len(data)))
                    if not piece:
                        break
                    data.extend(piece)
                return bytes(data)
            finally:
                connection.close()


def wave_bytes(samples, rate: int) -> bytes:
    """Wrap the samples in a wave file."""
    import io

    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(samples.tobytes())
    return buffer.getvalue()
