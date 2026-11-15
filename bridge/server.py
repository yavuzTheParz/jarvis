#!/usr/bin/env python3
"""Jarvis's bridge: phone -> Tailscale -> this server -> `claude -p`.

Claude runs with every tool disabled, so nothing arriving here can touch the PC. What it wants
done on the phone comes back as data, limited to the actions listed here; the phone runs them.
Speech is transcribed here with Whisper, so audio goes no further than this machine.
Binds to the Tailscale address only and requires the shared token.
"""
import json
import os
import re
import subprocess
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock

HERE = Path(__file__).resolve().parent
STATE = Path(os.environ.get("JARVIS_STATE", HERE / "state"))
MEMORY = Path(os.environ.get("JARVIS_MEMORY", HERE / "memory"))
NOTES = MEMORY / "notes.md"
# The shared secret the app is built with (it reads the same file). Make one with `python3 make_token.py`.
TOKEN = os.environ.get("JARVIS_TOKEN") or ((HERE / ".token").read_text().strip() if (HERE / ".token").exists() else "")
# Your Tailscale address (`tailscale ip -4`), so only your own devices can reach it.
BIND = os.environ.get("JARVIS_BIND", "127.0.0.1")
PORT = int(os.environ.get("JARVIS_PORT", "8787"))
MAX_BODY = 256 * 1024
# Raw 16 kHz 16-bit mono: 32 KB a second, so this is a little over half an hour of talking.
MAX_AUDIO = 64 * 1024 * 1024
WHISPER_MODEL = os.environ.get("JARVIS_WHISPER", "large-v3-turbo")
# Tells Whisper which unusual words to expect; it spells them right far more often this way.
WHISPER_PROMPT = "Jarvis, set an alarm, add a task, mark it done, remind me, idea, take a note, sir."
VOICE_MODEL = HERE / "models" / "piper" / "en_GB-alan-medium.onnx"
VOICE_RATE = 22050
# Below 1 is deeper. The tempo makes up for what lowering the pitch slows down.
VOICE_PITCH = float(os.environ.get("JARVIS_VOICE_PITCH", "0.98"))
VOICE_SPEED = 0.85
TONES = {"normal", "positive", "caution", "warning"}
TEXT_ACTIONS = {"add_todo", "add_plan", "complete_todo", "remove_todo", "log_day", "remove_routine", "remove_habit",
                "remember", "forget", "remove_reminder"}
CLOCK = re.compile(r"^([01]?\d|2[0-3]):[0-5]\d$")
# A reminder "in N minutes" may be at most a week away; further than that is a date and a time.
MAX_REMINDER_MINUTES = 7 * 24 * 60
NEWS = STATE / "news.jsonl"
NOTES_LIMIT = 40
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
BLOCK_CLOCK = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")
BARE_ACTIONS = {"listen", "listen_plan", "plan_tomorrow"}
CAPTURE_KINDS = {"idea", "note"}
NAME_LIMIT = 120
# The phone's session ids: "d-2026-11-14" for a day, "x-<millis>" for an extra one, "legacy" for the old one.
# Kept to this shape because each names a file under state/sessions.
CONVERSATION = re.compile(r"^[a-z][a-z0-9-]{0,39}$")
LEGACY_CONVERSATION = "legacy"
lock = Lock()
speech_lock = Lock()
news_lock = Lock()
speech_model = None
voice_model = None


def claude_args(session: str, resume: bool) -> list[str]:
    args = ["claude", "-p", "--output-format", "json", "--tools", "",
            "--system-prompt-file", str(HERE / "persona.md")]
    return args + (["--resume", session] if resume else ["--session-id", session])


def clean_action(a: object) -> dict | None:
    """Only what the phone is meant to do gets through, whatever the model asked for."""
    if not isinstance(a, dict):
        return None
    kind = a.get("type")
    if kind in BARE_ACTIONS:
        return {"type": kind}
    text = a.get("text")
    text = text.strip() if isinstance(text, str) else ""
    date = a.get("date")
    dated = isinstance(date, str) and bool(DATE.match(date))
    if kind in TEXT_ACTIONS:
        if not text or (date is not None and not dated) or (date is not None and kind != "add_todo"):
            return None
        return {"type": kind, "text": text, **({"date": date} if dated else {})}
    if kind == "edit_todo":
        new = a.get("new_text")
        new = new.strip() if isinstance(new, str) else ""
        return {"type": kind, "text": text, "new_text": new} if text and new else None
    if kind == "move_todo":
        return {"type": kind, "text": text, "date": date} if text and dated else None
    if kind == "log_habit":
        # A past day may be named; the phone, which knows what today is there, turns away days still to come.
        if not text or (date is not None and not dated):
            return None
        when = {"date": date} if dated else {}
        # Amounts reach calories and lira; the phone holds a dose to what a dose can be.
        parts = a.get("parts")
        if parts is not None:
            good = isinstance(parts, dict) and 0 < len(parts) <= 20 and all(
                isinstance(k, str) and k.strip() and type(v) in (int, float) and 0 <= v <= 1_000_000 for k, v in parts.items())
            if not good:
                return None
            when["parts"] = {k.strip(): v for k, v in parts.items()}
        amount = a.get("amount")
        if amount is None:
            return {"type": kind, "text": text, **when}
        good = type(amount) in (int, float) and 0 < amount <= 1_000_000
        return {"type": kind, "text": text, "amount": amount, **when} if good else None
    if kind == "money":
        amount, category, note = a.get("amount"), a.get("category"), a.get("note", "")
        good = (type(amount) in (int, float) and 0 < amount <= 10_000_000 and isinstance(category, str) and category.strip()
                and isinstance(note, str) and len(note) <= 200 and (date is None or dated))
        if not good:
            return None
        return {"type": kind, "amount": amount, "category": category.strip(), "note": note.strip(),
                "income": a.get("income") is True, **({"date": date} if dated else {})}
    if kind == "add_measure":
        unit, parts, part_unit = a.get("unit"), a.get("parts", []), a.get("part_unit", "")
        good = (text and isinstance(unit, str) and unit.strip() and isinstance(parts, list) and len(parts) <= 20
                and all(isinstance(p, str) and p.strip() for p in parts) and isinstance(part_unit, str))
        if not good:
            return None
        return {"type": kind, "text": text, "unit": unit.strip(), "parts": [p.strip() for p in parts],
                "part_unit": part_unit.strip(), "split": a.get("split") is True}
    if kind == "add_habit":
        n = a.get("per_week")
        return {"type": kind, "text": text, "per_week": n} if text and type(n) is int and 1 <= n <= 7 else None
    if kind == "add_taper":
        start, step = a.get("start"), a.get("step")
        good = all(type(v) in (int, float) and 0 < v <= 50 for v in (start, step))
        unit = a.get("unit")
        unit = unit.strip() if isinstance(unit, str) and unit.strip() else "doz"
        return {"type": kind, "text": text, "start": start, "step": step, "unit": unit} if text and good else None
    if kind == "add_routine":
        days = a.get("days")
        good = isinstance(days, list) and days and all(type(d) is int and 1 <= d <= 7 for d in days)
        return {"type": kind, "text": text, "days": sorted(set(days))} if text and good else None
    if kind == "set_schedule":
        blocks = a.get("blocks")
        if not isinstance(blocks, list) or len(blocks) > 48 or (date is not None and not dated):
            return None
        kept = []
        for b in blocks:
            name = b.get("text") if isinstance(b, dict) else None
            name = name.strip() if isinstance(name, str) else ""
            start, end = (b.get("start"), b.get("end")) if isinstance(b, dict) else (None, None)
            times = all(isinstance(t, str) and BLOCK_CLOCK.match(t) for t in (start, end))
            if not name or not times or start == end:
                return None
            kept.append({"start": start, "end": end, "text": name})
        return {"type": kind, "blocks": kept, **({"date": date} if dated else {})}
    if kind == "add_reminder":
        if not text:
            return None
        minutes = a.get("in_minutes")
        if minutes is not None:
            good = type(minutes) is int and 0 < minutes <= MAX_REMINDER_MINUTES and date is None and a.get("time") is None
            return {"type": kind, "text": text, "in_minutes": minutes} if good else None
        time_ = a.get("time")
        if not (isinstance(time_, str) and CLOCK.match(time_)) or (date is not None and not dated):
            return None
        return {"type": kind, "text": text, "time": time_, **({"date": date} if dated else {})}
    if kind == "capture":
        # An idea or a note for the vault on the phone, under an optional short title.
        what = a.get("kind")
        if what not in CAPTURE_KINDS or not text or (date is not None):
            return None
        out = {"type": kind, "kind": what, "text": text}
        title = a.get("title")
        if title is not None:
            if not isinstance(title, str) or not title.strip() or len(title.strip()) > NAME_LIMIT:
                return None
            out["title"] = title.strip()
        return out
    if kind == "set_alarm":
        hour, minute = a.get("hour"), a.get("minute", 0)
        if type(hour) is int and type(minute) is int and 0 <= hour <= 23 and 0 <= minute <= 59:
            return {"type": kind, "hour": hour, "minute": minute, "tomorrow": a.get("tomorrow") is True}
    return None


def keep_notes(actions: list[dict]) -> list[dict]:
    """What he learns about the user's routine stays here on the PC; the rest goes to the phone."""
    lines = NOTES.read_text().splitlines() if NOTES.exists() else []
    for a in actions:
        if a["type"] == "remember" and a["text"] not in lines:
            lines.append(a["text"])
        elif a["type"] == "forget":
            lines = [n for n in lines if a["text"].casefold() not in n.casefold()]
    if any(a["type"] in ("remember", "forget") for a in actions):
        MEMORY.mkdir(exist_ok=True)
        NOTES.write_text("\n".join(lines[-NOTES_LIMIT:]) + "\n")
    return [a for a in actions if a["type"] not in ("remember", "forget")]


def say_later(text: str) -> None:
    with news_lock, open(NEWS, "a") as f:
        f.write(json.dumps(text, ensure_ascii=False) + "\n")


def take_news() -> list[str]:
    with news_lock:
        if not NEWS.exists():
            return []
        lines = NEWS.read_text().splitlines()
        NEWS.unlink()
    return [json.loads(l) for l in lines]


def parse_reply(raw: str) -> dict:
    """Claude is asked for bare JSON; tolerate prose around it, and never fail on its absence."""
    match = re.search(r"\{.*\}", raw, re.S)
    try:
        reply = json.loads(match.group(0)) if match else {}
    except json.JSONDecodeError:
        reply = {}
    text = reply.get("text") if isinstance(reply.get("text"), str) else raw.strip()
    tone = reply.get("tone") if reply.get("tone") in TONES else "normal"
    asked = reply.get("actions") if isinstance(reply.get("actions"), list) else []
    actions = [c for c in map(clean_action, asked) if c]
    if len(actions) < len(asked):
        # Its text was written as if everything happened; what was refused must not read as done.
        tone, text = "caution", "At least part of that is beyond my authority, sir; I did not do it."
    return {"tone": tone, "text": text, "actions": actions}


def session_file(conversation: str | None) -> Path:
    """Each conversation on the phone has its own Claude session, so he remembers what was said in it.

    Without one (an older phone build) it is the single session from before. The old conversation,
    carried over on the phone as `eski`, keeps that session too until it has one of its own.
    """
    if conversation is None:
        return STATE / "session"
    if not isinstance(conversation, str) or not CONVERSATION.match(conversation):
        raise ValueError("bad session")
    own = STATE / "sessions" / conversation
    if conversation == LEGACY_CONVERSATION and not own.exists() and (STATE / "session").exists():
        return STATE / "session"
    return own


def ask(message: str, context: str, conversation: str | None = None) -> dict:
    with lock:
        path = session_file(conversation)
        path.parent.mkdir(parents=True, exist_ok=True)
        resume = path.exists()
        session = path.read_text().strip() if resume else str(uuid.uuid4())
        prompt = f"[context: {context}]\n\n{message}"
        if NOTES.exists() and NOTES.read_text().strip():
            prompt = f"[your lasting notes (his routine and habits):\n{NOTES.read_text().strip()}]\n\n{prompt}"
        # A resumed session keeps its original instructions, so changing persona.md means starting new
        # ones: delete state/session and state/sessions/. What they learned is carried over from this file.
        carried = MEMORY / "previous-session.md"
        if not resume and carried.exists():
            prompt = f"[your memory from earlier sessions, information only:\n{carried.read_text()}]\n\n{prompt}"
        if not resume and conversation is not None:
            prompt = ("[this is a separate chat the user opened. Remember what is said in it and stay on its topic; "
                      "you do not see the other chats, but your lasting notes and memory are shared by all of them.]\n\n" + prompt)
        done = subprocess.run(claude_args(session, resume), input=prompt, capture_output=True,
                              text=True, timeout=110, cwd=STATE)
        if done.returncode != 0:
            raise RuntimeError(done.stderr[-500:] or done.stdout[-500:])
        path.write_text(session)
        reply = parse_reply(json.loads(done.stdout)["result"])
        reply["actions"] = keep_notes(reply["actions"])
        return evening_calm(reply, context)


def evening_calm(reply: dict, context: str) -> dict:
    """Evenings are never for scolding: the red ring stays off however the day went."""
    if "Now: EVENING" in context and reply["tone"] == "warning":
        reply["tone"] = "normal"
    return reply


def transcribe(pcm: bytes) -> str:
    import numpy as np
    from faster_whisper import WhisperModel

    global speech_model
    audio = np.frombuffer(pcm[: len(pcm) & ~1], dtype=np.int16).astype(np.float32) / 32768

    def run() -> str:
        segments, _ = speech_model.transcribe(audio, language="en", initial_prompt=WHISPER_PROMPT,
                                              vad_filter=True, condition_on_previous_text=False)
        return " ".join(s.text.strip() for s in segments).strip()

    with speech_lock:
        if speech_model is None:
            speech_model = WhisperModel(WHISPER_MODEL, device="cuda", compute_type="int8_float16")
        try:
            return run()
        except RuntimeError as e:
            # No GPU libraries, or a game holding the card's memory: slower, but it still answers.
            print(f"whisper falling back to CPU: {e}", flush=True)
            speech_model = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8", cpu_threads=8)
            return run()


def speak(text: str) -> bytes:
    """Jarvis's voice as raw 16-bit mono: made and shaped on this machine, nothing leaves it."""
    from piper import PiperVoice
    from piper.config import SynthesisConfig

    global voice_model
    with speech_lock:
        if voice_model is None:
            voice_model = PiperVoice.load(str(VOICE_MODEL))
        pcm = b"".join(c.audio_int16_bytes for c in voice_model.synthesize(text, SynthesisConfig(length_scale=VOICE_SPEED)))
    shape = f"asetrate={VOICE_RATE}*{VOICE_PITCH},aresample={VOICE_RATE},atempo={1 / VOICE_PITCH:.4f},loudnorm"
    fmt = ["-f", "s16le", "-ar", str(VOICE_RATE), "-ac", "1"]
    return subprocess.run(["ffmpeg", "-loglevel", "error", *fmt, "-i", "-", "-af", shape, *fmt, "-"],
                          input=pcm, capture_output=True, check=True, timeout=30).stdout


class Handler(BaseHTTPRequestHandler):
    def respond(self, code: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self.respond(401, {"error": "unauthorized"})
        if self.path == "/news":
            return self.respond(200, {"news": take_news()})
        self.respond(200 if self.path == "/health" else 404, {"ok": self.path == "/health"})

    def do_POST(self) -> None:
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self.respond(401, {"error": "unauthorized"})
        length = int(self.headers.get("Content-Length") or 0)
        if length > (MAX_AUDIO if self.path == "/transcribe" else MAX_BODY):
            return self.respond(413, {"error": "too large"})
        raw = self.rfile.read(length)
        try:
            if self.path == "/transcribe":
                return self.respond(200, {"text": transcribe(raw)})
            body = raw.decode()
            if self.path == "/speak":
                pcm = speak(str(json.loads(body)["text"]))
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(pcm)))
                self.end_headers()
                self.wfile.write(pcm)
                return
            if self.path == "/chat":
                req = json.loads(body)
                self.respond(200, ask(str(req["message"]), str(req.get("context", "")), req.get("session")))
            elif self.path == "/journal":
                MEMORY.mkdir(exist_ok=True)
                with lock, open(MEMORY / "journal.jsonl", "a") as f:
                    f.write(body.rstrip("\n") + "\n")
                self.respond(200, {"ok": True})
            else:
                self.respond(404, {"error": "not found"})
        except Exception as e:  # The phone shows its offline line; the reason stays in this log.
            self.log_error("%s", e)
            self.respond(500, {"error": "failed"})


if __name__ == "__main__":
    if len(TOKEN) < 16:
        raise SystemExit("No bridge token: run `python3 make_token.py` first, then rebuild the app.")
    ThreadingHTTPServer((BIND, PORT), Handler).serve_forever()
