"""Self-hosted app updates: CI drops FinanceApp.apk + version.json into the release folder on the VPS."""
import json
import os
from pathlib import Path


def release_dir() -> Path:
    return Path(os.getenv("APP_RELEASE_DIR", "/app/app-release"))


def latest() -> dict | None:
    """The newest published build ({versionCode, versionName, sha256, size}) or None when nothing valid is there."""
    d = release_dir()
    meta, apk = d / "version.json", d / "FinanceApp.apk"
    try:
        info = json.loads(meta.read_text())
        code = int(info["versionCode"])
        if not apk.is_file():
            return None
        return {
            "versionCode": code,
            "versionName": str(info.get("versionName", "")),
            "sha256": str(info.get("sha256", "")),
            "size": apk.stat().st_size,
        }
    except (OSError, ValueError, KeyError, TypeError):
        return None


def apk_path() -> Path | None:
    p = release_dir() / "FinanceApp.apk"
    return p if latest() else None
