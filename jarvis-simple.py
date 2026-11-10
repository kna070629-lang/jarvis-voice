#!/usr/bin/env python3
"""
jarvis-simple.py — Simple Jarvis CLI using only termux-api
Works immediately without extra dependencies.
Wake word: says "Hey Jarvis" to activate, then uses termux-speech-to-text
"""

import sys
import os
import time
import subprocess
import threading

APP_DIR = os.path.dirname(os.path.abspath(__file__))

# Load face display
sys.path.insert(0, APP_DIR)
try:
    from face import JarvisFace
except ImportError:
    class JarvisFace:
        def set_emotion(self, e): pass
        def _render(self): pass


FACE = JarvisFace()
FACE.set_emotion("start")
print()
print("  ╭─────────╮")
print("  │  ◉ ◉  │")
print("  │  (o o) │")
print("  ╰─────────╯")
print()


def log(msg):
    print(f"  [jarvis] {msg}", flush=True)


def speak(text: str):
    """Use termux-tts-speak to say something."""
    try:
        subprocess.run(
            ["termux-tts-speak", text],
            capture_output=True, text=True, timeout=15
        )
    except Exception as e:
        log(f"TTS error: {e}")


def listen() -> str:
    """Use termux-speech-to-text to listen and transcribe."""
    try:
        result = subprocess.run(
            ["termux-speech-to-text"],
            capture_output=True, text=True, timeout=15
        )
        return result.stdout.strip()
    except subprocess.TimeoutExpired:
        return ""
    except Exception as e:
        log(f"STT error: {e}")
        return ""


def play_beep():
    """Play a short beep to indicate wake word detection."""
    try:
        # Simple audio beep using termux-tone or sox
        subprocess.run(
            ["termux-volume", "music", "5"],
            capture_output=True, timeout=2
        )
    except:
        pass


def get_response(text: str) -> str:
    """Use the shared local-first engine; no canned AI-like response."""
    from jarvis_engine import get_engine
    return get_engine("jarvis-simple").process(text)


def main():
    FACE.set_emotion("idle")
    log("Ready! Say 'Hey Jarvis' to activate...")
    print()
    print("  ╭─────────╮")
    print("  │  ¯\\_/¯  │")
    print("  │  (o o)  │")
    print("  ╰─────────╯")
    print()

    listening = False

    try:
        while True:
            # Listen for audio trigger (VAD simulation via termux)
            # For "Hey Jarvis" detection, we rely on the user pressing enter
            # or we can poll the microphone level using termux
            cmd = input(">>> ").strip().lower() if sys.platform != "android" else ""

            if "hey jarvis" in cmd or cmd == "jarvis":
                listening = True
                play_beep()
                FACE.set_emotion("excited")
                print()
                log("Listening...")
                print("  ╭─────────╮")
                print("  │  ◉ ◉  │")
                print("  │  (o o) │")
                print("  ╰─────────╯")
                print()

                text = listen()

                if text:
                    log(f"You said: {text}")
                    FACE.set_emotion("thinking")
                    print("  ╭─────────╮")
                    print("  │  ¯◡¯  │")
                    print("  │  ( -) │")
                    print("  ╰─────────╯")
                    print()

                    response = get_response(text)
                    log(f"Jarvis: {response}")
                    FACE.set_emotion("speaking")
                    speak(response)
                    FACE.set_emotion("happy")
                    print("  ╭─────────╮")
                    print("  │  ◉ ◉  │")
                    print("  │  (‿‿) │")
                    print("  ╰─────────╯")
                    print()
                else:
                    log("No speech detected.")
                    FACE.set_emotion("idle")

            elif cmd in ["quit", "exit", "bye", "stop"]:
                break

            else:
                if cmd and cmd != "":
                    print("Say 'Hey Jarvis' to activate...")
                time.sleep(0.5)

    except (KeyboardInterrupt, EOFError):
        pass

    print()
    print("  ╭─────────╮")
    print("  │  · ·  │")
    print("  │  (x x) │")
    print("  ╰─────────╯")
    print()
    log("Goodbye!")


if __name__ == "__main__":
    main()
