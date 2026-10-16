#!/usr/bin/env python3
"""Make a release notes file collapsible without flattening its headings.

A release page exists to hand somebody a file, and the notes sit above the downloads. A
release with a lot to say is exactly the release where "a lot to say" pushes the download
link below the fold — so the notes ship closed and the reader opens the language they read.

Two levels, and they behave differently on purpose:

* the **language** block starts closed, because that is the scroll nobody wants;
* each **section** inside starts open, because a reader who has chosen a language has
  chosen to read it, and a second round of clicking is a second round of work.

The section headings are drawn as real headings (`h3`, `h4`, …) inside their `<summary>`,
keeping the depth the file already had. Wrapping them in `<b>` — which the first version of
this script did — made a `##` and an `h4` look identical, which is what "你好像没排版" was
about: the notes had lost their outline.

    python tools/collapse-release-notes.py docs/releases/v0.4.0.md

Re-running is safe: a file that already contains `<details>` is left alone, so restore from
git first if the shape changes.
"""
from __future__ import annotations

import pathlib
import re
import sys

HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
RULE = re.compile(r"^-{3,}\s*$")
LANGUAGE_WORDS = {"english", "中文", "说明"}


def section_details(text: str) -> str:
    """One `<details open>` per heading, with the heading kept as a heading."""
    blocks: list[tuple[str | None, int, list[str]]] = []
    title: str | None = None
    depth = 0
    body: list[str] = []

    for line in text.split("\n"):
        match = HEADING.match(line.strip())
        if match:
            blocks.append((title, depth, body))
            title = match.group(2).strip()
            depth = len(match.group(1))
            body = []
        else:
            body.append(line)
    blocks.append((title, depth, body))

    depths = [level for title, level, _ in blocks if title is not None]
    top = min(depths) if depths else 2

    rendered: list[str] = []
    for heading, level, lines in blocks:
        inner = "\n".join(lines).strip("\n")
        if heading is None:
            if inner:
                rendered.append(inner)
            continue
        tag = "h%d" % min(6, 3 + (level - top))
        rendered.append(
            "<details open>\n<summary><%s>%s</%s></summary>\n\n%s\n\n</details>"
            % (tag, heading, tag, inner)
        )
    return "\n\n".join(rendered)


def collapse(path: pathlib.Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if "<details>" in text:
        return False

    lines = text.split("\n")
    split = next((index for index, line in enumerate(lines) if RULE.match(line.strip())), None)
    halves = (
        [("中文", "\n".join(lines[:split])), ("English", "\n".join(lines[split + 1:]))]
        if split is not None
        else [("说明", text)]
    )

    parts: list[str] = []
    for label, half in halves:
        # The `# CubSync 0.3.4` line is the release title, which GitHub already shows, and a
        # leading `## English` is this script's own marker rather than a section.
        half = re.sub(r"^#\s+.*$", "", half, count=1, flags=re.M)
        half = re.sub(
            r"^#{1,6}\s+(" + "|".join(LANGUAGE_WORDS) + r")\s*$",
            "",
            half,
            count=1,
            flags=re.M | re.I,
        )
        body = section_details(half.strip("\n"))
        if body:
            parts.append("<details>\n<summary><b>%s</b></summary>\n\n%s\n\n</details>" % (label, body))

    path.write_text("\n\n".join(parts) + "\n", encoding="utf-8")
    return True


def main(argv: list[str]) -> int:
    targets = [pathlib.Path(argument) for argument in argv[1:]]
    if not targets:
        targets = sorted(pathlib.Path("docs/releases").glob("v*.md"))
    changed = 0
    for target in targets:
        if not target.is_file():
            print("missing:", target, file=sys.stderr)
            continue
        if collapse(target):
            changed += 1
            print("collapsed", target)
        else:
            print("already collapsible, left alone:", target)
    print("%d file(s) rewritten" % changed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
