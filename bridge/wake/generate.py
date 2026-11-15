#!/usr/bin/env python3
"""Synthesizes training clips for the wake word with edge-tts. Usage: generate.py OUT_DIR"""
import asyncio
import random
import subprocess
import sys
from pathlib import Path

import edge_tts

OUT = Path(sys.argv[1])
# The name as English speakers say it, alone and with the usual openers.
NAMES = ["Jarvis", "Jarvis!", "Jarvis?", "Hey Jarvis", "Hey, Jarvis", "Okay Jarvis", "OK Jarvis", "Hi Jarvis", "Jarvis.", "Yo Jarvis"]
# Sounds close to the name, which must not wake him: this is what makes the model careful.
NEAR = ["Davis", "Travis", "Harvey", "Jarvie", "service", "nervous", "Charles", "jars", "carvings", "Marvin", "harvest", "Jervis",
        "Mavis", "Paris", "Jarred", "jargon", "Garvey", "Jarrett", "Avis", "Jasper", "carve this", "starve us", "far bliss",
        "hey Davis", "okay Travis", "hey service", "how's this", "Jarvis Street", "artist", "garlic", "Jacob", "Jordan", "jealous"]
SPEECH = [
    "I went to the market this morning and then came back home to read for a while.", "The meeting has been moved to three o'clock, please prepare accordingly.",
    "Harvest season is over and the service was excellent at the restaurant.", "James and Jerry went to Paris with Davis last year.",
    "Turn on the lights, play some music and tell me the weather.", "That movie was really good, especially the ending.",
    "I need to finish the report before Friday, otherwise my manager will be upset.", "Can you pass me the salt and the pepper, please?",
    "My phone keeps buzzing with notifications all day long.", "We should go for a walk after dinner if it doesn't rain.",
    "Travis said the service at the garage was surprisingly fast.", "Set a timer for ten minutes and remind me to check the oven.",
    "The kids are playing in the garden, I'll call them in soon.", "I can't decide what to do this weekend, maybe a hike.",
    "Marvin is nervous about the interview on Tuesday.", "Add milk and eggs to the shopping list, and some coffee too.",
]
PROSODY = [("+0%", "+0Hz"), ("-20%", "+0Hz"), ("+15%", "+0Hz"), ("+0%", "-25Hz"), ("+0%", "+25Hz"), ("-15%", "-15Hz"), ("+10%", "+15Hz")]


async def one(sem, text, voice, rate, pitch, kind, idx):
    wav = OUT / kind / f"{voice}__{idx:05d}.wav"
    if wav.exists():
        return
    async with sem:
        for attempt in range(3):
            try:
                mp3 = wav.with_suffix(".mp3")
                await edge_tts.Communicate(text, voice, rate=rate, pitch=pitch).save(str(mp3))
                subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(mp3), "-ar", "16000", "-ac", "1", str(wav)], check=True)
                mp3.unlink()
                return
            except Exception:
                await asyncio.sleep(1 + attempt)


async def main():
    random.seed(7)
    for k in ("pos", "neg"):
        (OUT / k).mkdir(parents=True, exist_ok=True)
    voices = [v["ShortName"] for v in await edge_tts.list_voices()]
    english = [v for v in voices if v.startswith("en-")]
    # A few other accents, so a non-native "Jarvis" still wakes him.
    others = [v for v in voices if v.split("-")[0] in ("de", "es", "fr", "it", "pt", "nl", "pl", "tr", "hi", "ja", "zh")][:30]
    jobs, i = [], 0

    def add(text, voice, prosody, kind):
        nonlocal i
        i += 1
        jobs.append((text, voice, prosody[0], prosody[1], kind, i))

    for v in english:
        for t in NAMES:
            for p in random.sample(PROSODY, 4):
                add(t, v, p, "pos")
        for t in random.sample(NEAR, 14):
            add(t, v, random.choice(PROSODY), "neg")
        for t in random.sample(SPEECH, 4):
            add(t, v, random.choice(PROSODY), "neg")
    for v in others:
        for t in ("Jarvis", "Hey Jarvis", "Okay Jarvis"):
            add(t, v, random.choice(PROSODY), "pos")
        for t in random.sample(NEAR, 4):
            add(t, v, random.choice(PROSODY), "neg")
    print(len(jobs), "clips from", len(english), "English and", len(others), "other voices", flush=True)
    sem = asyncio.Semaphore(8)
    await asyncio.gather(*(one(sem, *j) for j in jobs))


asyncio.run(main())
