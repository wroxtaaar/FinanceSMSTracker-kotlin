import json
from app import app_release


def test_latest_none_when_empty(tmp_path, monkeypatch):
    monkeypatch.setenv("APP_RELEASE_DIR", str(tmp_path))
    assert app_release.latest() is None
    assert app_release.apk_path() is None


def test_latest_reads_published_build(tmp_path, monkeypatch):
    monkeypatch.setenv("APP_RELEASE_DIR", str(tmp_path))
    (tmp_path / "FinanceApp.apk").write_bytes(b"x" * 10)
    (tmp_path / "version.json").write_text(json.dumps({"versionCode": 42, "versionName": "1.0.42", "sha256": "ab"}))
    info = app_release.latest()
    assert info == {"versionCode": 42, "versionName": "1.0.42", "sha256": "ab", "size": 10}
    assert app_release.apk_path() == tmp_path / "FinanceApp.apk"


def test_latest_ignores_broken_metadata_or_missing_apk(tmp_path, monkeypatch):
    monkeypatch.setenv("APP_RELEASE_DIR", str(tmp_path))
    (tmp_path / "version.json").write_text("{not json")
    assert app_release.latest() is None
    (tmp_path / "version.json").write_text(json.dumps({"versionCode": 3}))
    assert app_release.latest() is None  # no apk
