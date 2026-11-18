"""Validate EVCam documentation limits, rule redirects, and relative links."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]

MAX_LINES = 80
MAX_CHARS = 6000
MAX_RECENT_BULLETS = 5
MAX_PITFALLS = 20


def check_limits_and_recent(path: Path, recent_heading: str) -> tuple[int, int]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if len(lines) > MAX_LINES or len(text) > MAX_CHARS:
        raise AssertionError(f"{path.relative_to(ROOT)} exceeds limits: {len(lines)} lines, {len(text)} chars")
    if recent_heading in text:
        section = text.split(recent_heading, 1)[1].split("\n## ", 1)[0]
        bullets = re.findall(r"^- \d{4}-\d{2}-\d{2}", section, re.M)
        if len(bullets) > MAX_RECENT_BULLETS:
            raise AssertionError(f"{path.relative_to(ROOT)} has {len(bullets)} recent entries (max {MAX_RECENT_BULLETS})")
    return len(lines), len(text)


def check_links(paths: list[Path]) -> list[tuple[str, str]]:
    broken: list[tuple[str, str]] = []
    for path in paths:
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r"\[[^\]]+\]\(([^)]+)\)", text):
            raw = match.group(1).strip("<>").split("#", 1)[0]
            if not raw or re.match(r"^(https?|mailto|file):", raw) or raw.startswith("/"):
                continue
            if not (path.parent / raw).exists():
                broken.append((path.relative_to(ROOT).as_posix(), raw))
    return broken


def main() -> int:
    for rel in (
        "CLAUDE.md",
        ".cursor/rules/code-maintainability.mdc",
        ".cursor/rules/version-name-timestamp.mdc",
        ".cursor/rules/windows-shell-encoding.mdc",
    ):
        p = ROOT / rel
        if not p.exists():
            raise AssertionError(f"Missing rule adapter: {rel}")
        text = p.read_text(encoding="utf-8")
        if "AGENTS.md" not in text or len(text.splitlines()) > 12:
            raise AssertionError(f"Invalid rule adapter: {rel}")
        if rel.endswith(".mdc") and "alwaysApply: true" not in text:
            raise AssertionError(f"Missing alwaysApply in {rel}")

    if (ROOT / "logcat_debug.txt").exists():
        raise AssertionError("Stale logcat_debug.txt still exists at repo root")

    start_lines, start_chars = check_limits_and_recent(ROOT / "START_HERE.md", "## ")
    status_lines, status_chars = check_limits_and_recent(ROOT / "docs/STATUS.md", "## 最近更新")

    active_tasks = sorted((ROOT / "docs/tasks").glob("*.md"))
    task_stats = {}
    for task_file in active_tasks:
        t_lines, t_chars = check_limits_and_recent(task_file, "最近记录")
        task_stats[task_file.name] = (t_lines, t_chars)

    pitfalls_text = (ROOT / "docs/PITFALLS.md").read_text(encoding="utf-8")
    pitfalls_count = len(re.findall(r"^\| P\d+", pitfalls_text, re.M))
    if pitfalls_count > MAX_PITFALLS:
        raise AssertionError(f"docs/PITFALLS.md has {pitfalls_count} rows (max {MAX_PITFALLS})")

    md_files = [
        ROOT / "AGENTS.md",
        ROOT / "CLAUDE.md",
        ROOT / "START_HERE.md",
        ROOT / "README.md",
        *sorted((ROOT / "docs").rglob("*.md")),
    ]
    broken = check_links(md_files)
    if broken:
        details = "\n".join(f"  {src} -> {tgt}" for src, tgt in broken)
        raise AssertionError(f"Broken relative links ({len(broken)}):\n{details}")

    print(
        f"OK: START_HERE={start_lines}L/{start_chars}C, "
        f"STATUS={status_lines}L/{status_chars}C, "
        f"tasks={len(active_tasks)} {task_stats}, "
        f"pitfalls={pitfalls_count}, checked_md={len(md_files)}, broken_links=0"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
