#!/usr/bin/env python3
"""Render the guide's Markdown subset to portable, printable HTML (stdlib only)."""
from html import escape
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
GUIDE = ROOT / "docs/user-guide/user-guide.md"


def inline(text):
    tokens = []

    def keep(value):
        tokens.append(value)
        return f"\x00{len(tokens) - 1}\x00"

    text = re.sub(r"`([^`]+)`", lambda m: keep(f"<code>{escape(m[1])}</code>"), text)
    text = re.sub(
        r"(!?)\[([^\]]+)\]\(([^)]+)\)",
        lambda m: keep(
            f'<img src="{escape(m[3], quote=True)}" alt="{escape(m[2], quote=True)}">'
            if m[1] else f'<a href="{escape(m[3], quote=True)}">{inline(m[2])}</a>'
        ),
        text,
    )
    text = escape(text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    return re.sub(r"\x00(\d+)\x00", lambda m: tokens[int(m[1])], text)


def render(source):
    lines = source.splitlines()
    out, contents = [], []
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        heading = re.match(r"^(#{1,6}) (.+)$", line)
        if heading:
            level, title = len(heading[1]), heading[2]
            anchor = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
            out.append(f'<h{level} id="{anchor}">{inline(title)}</h{level}>')
            if level == 2:
                contents.append(f'<li><a href="#{anchor}">{inline(title)}</a></li>')
            i += 1
        elif line.startswith("| "):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip() for c in lines[i].strip("|").split("|")])
                i += 1
            head = "".join(f"<th scope=\"col\">{inline(c)}</th>" for c in rows[0])
            body = "".join("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in row) + "</tr>" for row in rows[2:])
            out.append(f'<div class="table-wrap"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>')
        elif re.match(r"^(?:- |\d+\. )", line):
            ordered = bool(re.match(r"^\d+\. ", line))
            pattern = r"^\d+\. (.*)$" if ordered else r"^- (.*)$"
            items = []
            while i < len(lines) and (item := re.match(pattern, lines[i])):
                items.append(f"<li>{inline(item[1])}</li>")
                i += 1
            tag = "ol" if ordered else "ul"
            out.append(f"<{tag}>" + "".join(items) + f"</{tag}>")
        else:
            paragraph = []
            while i < len(lines) and lines[i].strip():
                paragraph.append(lines[i])
                i += 1
            out.append(f'<p>{inline(" ".join(paragraph))}</p>')
    out.insert(2, '<nav aria-label="Contents"><h2>Contents</h2><ol>' + "".join(contents) + '</ol></nav>')
    return "\n".join(out)


STYLE = """
html { color-scheme: light; scroll-behavior: smooth; }
body { margin: 0; color: #20262e; background: #fff;
  font: 16px/1.65 system-ui, sans-serif; }
main { max-width: 900px; padding: 40px 24px 80px; margin: auto; }
h1 { font-size: 2.5rem; line-height: 1.15; }
h2 { margin-top: 2.5rem; border-top: 1px solid #ccd3da; padding-top: 1rem; }
h3 { margin-top: 1.8rem; }
a { color: #155e91; text-underline-offset: .15em; }
li { margin-bottom: .5rem; }
code { background: #f0f3f5; padding: .1em .3em; border-radius: 3px;
  overflow-wrap: anywhere; font-size: .9em; }
nav { background: #f5f7f9; padding: 12px 24px; border-radius: 8px; }
nav h2 { border: 0; margin-top: 0; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: .93rem; }
th, td { text-align: left; vertical-align: top; padding: 10px;
  border: 1px solid #ccd3da; }
th { background: #edf1f4; }
img { max-width: 100%; height: auto; }
@media print {
  body { font-size: 10pt; } main { max-width: none; padding: 0; }
  h1, h2, h3 { break-after: avoid; } tr { break-inside: avoid; }
  a { color: inherit; } .table-wrap { overflow: visible; }
  @page { margin: 18mm; }
}
"""


if __name__ == "__main__":
    body = render(GUIDE.read_text())
    output = GUIDE.with_suffix(".html")
    output.write_text(
        '<!DOCTYPE html>\n<html lang="en"><head><meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        f'<title>RawLens user guide</title><style>{STYLE}</style></head>\n'
        f'<body><main>\n{body}\n</main></body></html>\n'
    )
    print(f"Rendered {output.relative_to(ROOT)}")
