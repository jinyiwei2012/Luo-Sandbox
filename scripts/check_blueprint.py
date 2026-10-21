#!/usr/bin/env python3
"""Validate the structural integrity of the Luo-Sandbox blueprint repository."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit


REQUIRED_FILES = (
    "README.md",
    "LICENSE",
    "NOTICE.md",
    "docs/architecture.md",
    "docs/roadmap.md",
    "docs/mineflayer-blueprint.md",
    "docs/provenance.md",
)
SENSITIVE_BASENAMES = {
    ".env",
    ".env.local",
    ".env.production",
    ".env.development",
    "credentials.json",
    "secrets.json",
    "token.json",
    "tokens.json",
    "auth.json",
}
SENSITIVE_PARTS = {"profilesfolder", "auth-cache", "token-cache"}
INLINE_LINK_RE = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
REFERENCE_LINK_RE = re.compile(r"^\s*\[[^\]]+\]:\s*(\S+)", re.MULTILINE)


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def report(errors: list[str], message: str) -> None:
    errors.append(message)


def check_required_files(root: Path, errors: list[str]) -> None:
    for relative_path in REQUIRED_FILES:
        if not (root / relative_path).is_file():
            report(errors, f"missing required file: {relative_path}")


def is_external_or_anchor(link: str) -> bool:
    parsed = urlsplit(link)
    return bool(parsed.scheme or parsed.netloc or link.startswith("#"))


def local_link_target(link: str) -> str | None:
    link = link.strip()
    if link.startswith("<") and link.endswith(">"):
        link = link[1:-1].strip()
    if not link or is_external_or_anchor(link):
        return None
    return unquote(urlsplit(link).path)


def check_markdown_links(root: Path, errors: list[str]) -> None:
    for markdown_file in root.rglob("*.md"):
        if ".git" in markdown_file.parts:
            continue
        content = markdown_file.read_text(encoding="utf-8")
        links = INLINE_LINK_RE.findall(content) + REFERENCE_LINK_RE.findall(content)
        for raw_link in links:
            target = local_link_target(raw_link)
            if target is None:
                continue
            if target.startswith("/") or PurePosixPath(target).is_absolute():
                report(errors, f"{markdown_file.relative_to(root)}: absolute local link is not allowed: {raw_link}")
                continue
            resolved = (markdown_file.parent / target).resolve()
            try:
                resolved.relative_to(root.resolve())
            except ValueError:
                report(errors, f"{markdown_file.relative_to(root)}: link escapes repository: {raw_link}")
                continue
            if not resolved.exists():
                report(errors, f"{markdown_file.relative_to(root)}: missing link target: {raw_link}")


def check_json_files(root: Path, errors: list[str]) -> None:
    for json_file in root.rglob("*.json"):
        if ".git" in json_file.parts or "node_modules" in json_file.parts:
            continue
        try:
            json.loads(json_file.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            report(errors, f"{json_file.relative_to(root)}: invalid JSON: {exc}")


def git_tracked_files(root: Path) -> list[str] | None:
    result = subprocess.run(
        ["git", "rev-parse", "--is-inside-work-tree"],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0 or result.stdout.strip() != "true":
        return None
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return None
    return [path for path in result.stdout.split("\0") if path]


def check_tracked_sensitive_files(root: Path, errors: list[str]) -> None:
    tracked_files = git_tracked_files(root)
    if tracked_files is None:
        print("Git repository not initialized; tracked-sensitive-file check skipped.")
        return
    for tracked_file in tracked_files:
        path = PurePosixPath(tracked_file)
        filename = path.name.lower()
        is_environment_file = filename == ".env" or (
            filename.startswith(".env.") and filename != ".env.example"
        )
        if (filename in SENSITIVE_BASENAMES or is_environment_file) or any(
            part.lower() in SENSITIVE_PARTS for part in path.parts
        ):
            report(errors, f"sensitive file must not be tracked: {tracked_file}")


def main() -> int:
    root = repo_root()
    errors: list[str] = []
    check_required_files(root, errors)
    check_markdown_links(root, errors)
    check_json_files(root, errors)
    check_tracked_sensitive_files(root, errors)

    if errors:
        print("Blueprint validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1
    print("Blueprint structural validation passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
