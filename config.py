"""
config.py — Jarvis configuration
"""

import os
import json
from pathlib import Path


class Config:
    def __init__(self, config_path: str = None, verbose: bool = False):
        self.verbose = verbose

        # Default config
        self.mic_device = None  # None = default
        self.sample_rate = 16000
        self.audio_chunk_size = 1024
        
        # Wake word
        self.wake_word = "hey jarvis"
        self.wake_word_case_sensitive = False
        
        # Voice file (custom audio file for Jarvis voice)
        # Set this to a path (relative to APP_DIR or absolute) to use a custom voice file
        # Supported formats: .wav, .mp3, .m4a
        # If set, TTS will play this file instead of generating speech
        self.voice_file = os.environ.get("JARVIS_VOICE_FILE", "")
        
        # STT (Speech-to-Text)
        # Options: "openai", "termux-api", "local", "faster-whisper"
        self.stt_engine = os.environ.get("JARVIS_STT_ENGINE", "termux-api")
        self.openai_api_key = os.environ.get("OPENAI_API_KEY", "")
        
        # TTS (Text-to-Speech)
        # Options: "termux-tts", "elevenlabs", "openai-tts"
        self.tts_engine = os.environ.get("JARVIS_TTS_ENGINE", "termux-tts")
        self.elevenlabs_api_key = os.environ.get("ELEVENLABS_API_KEY", "")
        self.elevenlabs_voice_id = os.environ.get("ELEVENLABS_VOICE_ID", "")
        self.elevenlabs_model_id = os.environ.get(
            "ELEVENLABS_MODEL_ID", "eleven_monolingual_v1"
        )

        # OpenCode integration (local `opencode serve` HTTP API)
        self.opencode_host = os.environ.get("JARVIS_OPENCODE_HOST", "127.0.0.1")
        self.opencode_port = int(os.environ.get("JARVIS_OPENCODE_PORT", "4096"))
        self.opencode_provider = os.environ.get("JARVIS_OPENCODE_PROVIDER", "opencode")
        self.opencode_model = os.environ.get(
            "JARVIS_OPENCODE_MODEL", "nemotron-3.5-lightning-free"
        )
        self.opencode_agent = os.environ.get("JARVIS_OPENCODE_AGENT", "build")
        self.opencode_session_key = os.environ.get("JARVIS_SESSION_KEY", "jarvis")

        # WhatsApp integration
        self.whatsapp_enabled = os.environ.get("JARVIS_WHATSAPP_ENABLED", "false").lower() == "true"
        self.whatsapp_number = os.environ.get("JARVIS_WHATSAPP_NUMBER", "")
        
        # Calling integration
        self.calling_enabled = os.environ.get("JARVIS_CALLING_ENABLED", "false").lower() == "true"

        # Load from file if exists
        if config_path:
            self.load_from_file(config_path)

    def load_from_file(self, path: str):
        path = Path(path)
        if not path.exists():
            return
        with open(path) as f:
            data = json.load(f)
        for key, value in data.items():
            setattr(self, key, value)

    def log(self, msg: str):
        if self.verbose:
            print(f"[Jarvis] {msg}")

    def to_dict(self) -> dict:
        return {k: v for k, v in self.__dict__.items() if not k.startswith("_")}