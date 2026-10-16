#!/usr/bin/env python3
"""Make a release notes file collapsible without flattening its headings.

A release page exists to hand somebody a file, and the notes sit above the downloads. A
release with a lot to say is exactly the release where "a lot to say" pushes the download
link below the fold — so the notes ship closed and the reader opens the language they read.

**One level collapses: the language.** The sections inside it are ordinary headings, and the
reader who opened a language gets the whole thing at once.

Sections used to collapse too, each one `<details open>` inside the language block. That was
dropped, for a reason that is GitHub's and not a taste: a nested `<details>` gets no
indentation. Its arrow sits at the same x as its parent's, so the two levels read as one
level, and the only way to fake the indent is to wrap the inner block in a blockquote and
live with the vertical rule it draws. On top of that the sections opened by default, so the
second level of disclosure was a control that never had anything to disclose — the same
content, one click more fragile.

The section headings are drawn as real headings, one level below the language label
(`h4`, `h5`, …), keeping the depth the file already had. Wrapping them in `<b>` — which the
first version of this script did — made a `##` and an `h4` look identical, which is what
"你好像没排版" was about: the notes had lost their outline. The language label is a real
heading too, one level above: with a plain `<b>` on the label and `h3` on the sections, the
child rendered larger than its parent.

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
LANGUAGE_WORDS = {"english", "中文", "说明", "中文说明"}


def section_headings(text: str) -> str:
    """Keep every heading a heading, one level below the language label, nothing collapsed."""
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
        hashes = "#" * min(6, 4 + (level - top))
        rendered.append("%s %s\n\n%s" % (hashes, heading, inner))
    return "\n\n".join(rendered)


def collapse(path: pathlib.Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if "<details>" in text:
        return False

    lines = text.split("\n")
    split = next((index for index, line in enumerate(lines) if RULE.match(line.strip())), None)
    halves = (
        [("中文说明", "\n".join(lines[:split])), ("English", "\n".join(lines[split + 1:]))]
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
        body = section_headings(half.strip("\n"))
        if body:
            parts.append(
                "<details>\n<summary><h3>%s</h3></summary>\n\n%s\n\n</details>" % (label, body)
            )

    # `.gitattributes` says `*.md text eol=lf`, so write LF even on Windows rather than
    # letting the platform default put CRLF in the working tree.
    path.write_text("\n\n".join(parts) + "\n", encoding="utf-8", newline="\n")
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
