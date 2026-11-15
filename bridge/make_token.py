#!/usr/bin/env python3
"""Writes bridge/.token, the secret shared by the bridge and the app. The app build reads it too, so rebuild after."""
import secrets
from pathlib import Path

path = Path(__file__).resolve().parent / ".token"
if path.exists():
    print(f"{path} already exists; delete it first to make a new one.")
else:
    path.write_text(secrets.token_urlsafe(32) + "\n")
    path.chmod(0o600)
    print(f"Wrote {path}. Rebuild and reinstall the app so it carries the same token.")
