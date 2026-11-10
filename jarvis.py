#!/usr/bin/env python3
"""
jarvis.py — Jarvis Voice Assistant (CLI)
Listens for "Hey Jarvis", transcribes speech, responds with TTS and emoji face.
"""

import sys
import os
import time
import threading
import argparse
import readline
from pathlib import Path

# Add jarvis-voice to path
APP_DIR = Path(__file__).parent
sys.path.insert(0, str(APP_DIR))

from face import JarvisFace
from audio import AudioManager
from wakeword import WakeWordDetector
from stt import STTEngine
from tts import TTSEngine
from config import Config

VERSION = "0.1.0"


class Jarvis:
    def __init__(self, config: Config):
        self.config = config
        self.face = JarvisFace()
        self.audio = AudioManager(config)
        self.wakeword = WakeWordDetector(config, self.on_wake)
        self.stt = STTEngine(config)
        self.tts = TTSEngine(config)
        self.running = False
        self.conversation_active = False
        self.waiting_for_command = False
        self.pending_whatsapp = None
        self.pending_call = None

    def on_wake(self):
        """Called when wake word is detected."""
        self.face.set_emotion("excited")
        print("\n🎤 Wake word detected — listening...")
        self.listen_and_respond()

    def send_whatsapp(self, message: str):
        """Send a WhatsApp message."""
        if not self.config.whatsapp_enabled or not self.config.whatsapp_number:
            print("⚠️ WhatsApp not configured — skipping.")
            return

        number = self.config.whatsapp_number
        # WhatsApp wants plain digits with the country code and no plus sign.
        digits = "".join(ch for ch in number if ch.isdigit())
        if not digits:
            print("❌ WhatsApp number is not valid.")
            return

        import subprocess
        from urllib.parse import quote

        # The URI must be a single argument and the text must be encoded,
        # otherwise spaces break the link and WhatsApp never opens.
        uri = f"whatsapp://send?phone={digits}&text={quote(message)}"
        try:
            subprocess.run(
                ["termux-open", uri],
                capture_output=True, text=True, timeout=10
            )
            print(f"📱 WhatsApp opened for +{digits} with message: {message}")
        except Exception as e:
            print(f"❌ WhatsApp error: {e}")

    def make_call(self, number: str):
        """Make a phone call."""
        if not self.config.calling_enabled:
            print("⚠️ Calling not configured — skipping.")
            return

        # Ensure number has country code
        if not number.startswith("+"):
            number = "+" + number

        import subprocess
        try:
            subprocess.run(
                ["termux-open", f"tel:{number}"],
                capture_output=True, text=True, timeout=10
            )
            print(f"📞 Call initiated to {number}")
        except Exception as e:
            print(f"❌ Call error: {e}")

    def listen_and_respond(self):
        """Listen for speech, process, respond, loop until silence."""
        self.conversation_active = True
        silence_count = 0
        max_silence = 2

        while self.conversation_active and silence_count < max_silence:
            self.face.set_emotion("listening")
            text = self.stt.listen()
            
            if text:
                silence_count = 0
                self.face.set_emotion("thinking")
                print(f"\n👤 You: {text}")

                # Check for special commands
                text_lower = text.lower().strip()

                if "whatsapp" in text_lower or re.search(r"send (?:a )?message", text_lower):
                    # Extract the message from the original text so its casing
                    # and punctuation survive.
                    parts = re.split(r"whatsapp|send (?:a )?message", text, maxsplit=1, flags=re.IGNORECASE)
                    message = parts[1].strip() if len(parts) > 1 else ""
                    message = re.sub(r"^(?:to|for|saying|that says)\s+", "", message, flags=re.IGNORECASE).strip()

                    if message:
                        self.send_whatsapp(message)
                        self.face.set_emotion("happy")
                        print("📱 WhatsApp message sent!")
                        time.sleep(1)
                        continue  # Go back to listening
                    else:
                        self.face.set_emotion("thinking")
                        print("🤖 Please specify the message content.")
                        time.sleep(1)
                        continue

                elif "call" in text_lower or "phone" in text_lower:
                    # Extract number after "call" or "phone"
                    for keyword in ["call", "phone"]:
                        if keyword in text_lower:
                            parts = re.split(rf"\b{keyword}\b", text, maxsplit=1, flags=re.IGNORECASE)
                            if len(parts) > 1:
                                number = parts[1].strip()
                                # Remove common words like "to", "for"
                                for word in ["to", "for", "number"]:
                                    number = re.sub(rf"\b{word}\b", "", number, flags=re.IGNORECASE).strip()
                                if number:
                                    self.make_call(number)
                                    self.face.set_emotion("happy")
                                    print(f"📞 Call initiated to {number}!")
                                    time.sleep(1)
                                    continue
                            break
                    else:
                        self.face.set_emotion("thinking")
                        print("🤖 Please specify the phone number.")
                        time.sleep(1)
                        continue
                
                # Get response from stdin (connected to OpenClaw)
                # For standalone mode, use terminal input
                response = self.get_response(text)
                
                if response:
                    self.face.set_emotion("speaking")
                    print(f"🤖 Jarvis: {response}")
                    self.tts.speak(response)
                    self.face.set_emotion("happy")
                    time.sleep(0.5)
            else:
                silence_count += 1
                if silence_count >= max_silence:
                    self.face.set_emotion("idle")
                    print("💤 Going back to sleep...")
                    self.conversation_active = False

    def get_response(self, text: str) -> str:
        """Override this to connect to OpenClaw or other AI backend."""
        # For now, simple echo + basic response
        text_lower = text.lower().strip()
        
        if "hello" in text_lower or "hi" in text_lower:
            return "Hello! How can I help you today?"
        elif "how are you" in text_lower:
            return "I'm doing great, thanks for asking! Ready to help."
        elif "your name" in text_lower:
            return "My name is Jarvis, your personal AI assistant."
        elif "thank" in text_lower:
            return "You're welcome!"
        elif "bye" in text_lower or "goodbye" in text_lower:
            return "Goodbye! I'll be here when you need me."
        elif text_lower.startswith(("what", "how", "why", "when", "where", "who")):
            return f"That's an interesting question. I'm not sure I have the answer right now, but I'm always learning."
        elif "time" in text_lower:
            import datetime
            now = datetime.datetime.now()
            return f"It's {now.strftime('%I:%M %p')}."
        elif "date" in text_lower:
            import datetime
            now = datetime.datetime.now()
            return f"Today is {now.strftime('%A, %B %d')}"
        elif "joke" in text_lower:
            return "Why did the AI cross the road? To get to the other side of the neural network!"
        elif "whatsapp" in text_lower:
            return "I can help you send WhatsApp messages. Just say 'Hey Jarvis' and 'WhatsApp [message]'."
        elif "call" in text_lower or "phone" in text_lower:
            return "I can help you make phone calls. Just say 'Hey Jarvis' and 'Call [number]'."
        else:
            return "Got it. Let me think about that."

    def start(self):
        """Start all components."""
        print(f"\n🤖 Jarvis v{VERSION} — Starting up...")
        self.face.set_emotion("starting")
        
        # Check permissions
        if not self.audio.check_permissions():
            print("❌ Microphone permission required. Please grant it and restart.")
            sys.exit(1)
        
        # Start wake word detection
        self.wakeword.start()
        self.face.set_emotion("idle")
        print("👂 Listening for 'Hey Jarvis'... (Ctrl+C to quit)\n")
        
        self.running = True
        try:
            while self.running:
                time.sleep(0.5)
        except KeyboardInterrupt:
            print("\n\n🛑 Shutting down...")
            self.stop()

    def stop(self):
        self.running = False
        self.wakeword.stop()
        self.audio.stop()
        self.face.set_emotion("off")
        print("👋 Goodbye!")


def main():
    parser = argparse.ArgumentParser(description="Jarvis Voice Assistant")
    parser.add_argument("--config", "-c", default=None, help="Config file path")
    parser.add_argument("--verbose", "-v", action="store_true", help="Verbose logging")
    args = parser.parse_args()

    config = Config(config_path=args.config, verbose=args.verbose)
    jarvis = Jarvis(config)
    jarvis.start()


if __name__ == "__main__":
    main()