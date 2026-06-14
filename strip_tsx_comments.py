#!/usr/bin/env python3
"""Remove comments from TSX/JSX/TS/KT files and clean up empty {} left after removal."""

import re
import sys
from pathlib import Path

SUPPORTED_EXTS = {'.tsx', '.jsx', '.ts', '.kt'}


def strip_comments(text: str, ext: str) -> str:
    is_jsx = ext in ('.tsx', '.jsx')

    if is_jsx:
        text = re.sub(r'\{\s*/\*.*?\*/\s*\}', '', text, flags=re.DOTALL)

    text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)

    lines = []
    for line in text.splitlines():
        stripped = re.sub(r'(?<!:)\s*//[^\n]*$', '', line)
        if stripped.strip() or not re.match(r'^\s*//', line):
            lines.append(stripped)
        else:
            lines.append('')
    text = '\n'.join(lines)

    if is_jsx:
        text = re.sub(r'\{\s*\}', '', text)

    text = re.sub(r'\n{3,}', '\n\n', text)
    return text


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <file> [file2 ...]")
        print(f"       {sys.argv[0]} --dir <directory>")
        sys.exit(1)

    files = []
    if sys.argv[1] == '--dir':
        d = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('.')
        for ext in SUPPORTED_EXTS:
            files.extend(d.rglob(f'*{ext}'))
    else:
        files = [Path(f) for f in sys.argv[1:]]

    for fp in files:
        if not fp.is_file():
            print(f"skip: {fp} (not found)")
            continue
        if fp.suffix not in SUPPORTED_EXTS:
            print(f"skip: {fp} (unsupported extension)")
            continue
        original = fp.read_text(encoding='utf-8')
        cleaned = strip_comments(original, fp.suffix)
        if cleaned != original:
            fp.write_text(cleaned, encoding='utf-8')
            print(f"cleaned: {fp}")
        else:
            print(f"no change: {fp}")


if __name__ == '__main__':
    main()
