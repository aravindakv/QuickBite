#!/usr/bin/env python3
"""
Extract the code files from a QuickBite guide chapter into your repo.

Usage (run from the repo root, e.g. ~/.../QuickBite/quickbite):
    python3 extract_code.py path/to/02-monorepo-gradle-and-common-lib.md            # dry run: shows what it would write
    python3 extract_code.py path/to/02-monorepo-gradle-and-common-lib.md --write    # writes the files
    python3 extract_code.py path/to/03-*.md --write --force                         # also overwrite existing files

How it works:
  * A code block is extracted only when the line right before it (ignoring blank lines) is a caption like
        `libs/common/build.gradle.kts`
        `context/Headers.java`
        Root `settings.gradle.kts`
        `libs/common/src/main/resources/quickbite-defaults.yml`: every service imports this file.
  * Relative captions (e.g. `context/Headers.java`) are resolved against the most recent
    "... lives under `some/base/dir/`" sentence in the chapter.
  * Captions that describe a PARTIAL change ("add", "replace", "merge", "change", "in `X`", ...) are
    reported as SNIPPET and never written: apply those by hand, because they modify an existing file.
  * Existing files are never overwritten unless you pass --force.
"""
import argparse
import os
import re
import sys

ROOT_PREFIXES = ("gradle/", "build-logic/", "libs/", "services/", "deploy/", "scripts/", "analytics/",
                 "clients/", "demo-data/", "contracts/")
ROOT_FILES = {"settings.gradle.kts", "build.gradle.kts", ".dockerignore", ".gitignore", ".env"}
CAPTION = re.compile(r"^(?:Root\s+|Create\s+|Helper script\s+)?`([^`\s]+)`(.*)$")
BASE = re.compile(r"(?:lives?|are|is) (?:under|in) `([^`]+)`")
PARTIAL = re.compile(r"\b(add|adds|replace|replaces|merge|change|update|insert|append|extend|excerpt)\b", re.I)
FILE_LIKE = re.compile(r"[\w.-]+\.(java|kt|kts|yml|yaml|json|sql|toml|xml|conf|template|sh|properties|js|imports|md)$|"
                       r"(^|/)(Dockerfile|\.dockerignore|\.gitignore|[\w.-]+\.imports)$")


def resolve(path, base, module_root=None):
    if path.startswith(ROOT_PREFIXES) or path in ROOT_FILES:
        return path
    if path.startswith(("app/src/", "app/build.gradle")):  # Android module lives in clients/android/
        return "clients/android/" + path
    if path.startswith("src/") and module_root:          # e.g. src/main/resources/... inside the current service
        return module_root + path
    if base:
        full = base.rstrip("/") + "/" + path
        return "clients/android/" + full if full.startswith("app/src/") else full
    return None


def extract(md_text):
    lines = md_text.splitlines()
    base, module_root, results, i = None, None, [], 0
    last_caption = None           # (path_text, rest, line_no)
    while i < len(lines):
        line = lines[i]
        m = BASE.search(line)
        if m:
            base = m.group(1)
            mr = re.match(r"^((?:services|libs|analytics)/[^/]+/)", base)
            if mr:
                module_root = mr.group(1)
        if line.startswith("```"):
            fence_lang = line[3:].strip()
            j = i + 1
            body = []
            while j < len(lines) and not lines[j].startswith("```"):
                body.append(lines[j])
                j += 1
            if last_caption and last_caption[2] == i:   # caption directly before this block
                path_text, rest, _ = last_caption
                target = resolve(path_text, base, module_root)
                mr = re.match(r"^((?:services|libs|analytics)/[^/]+/)", target or "")
                if mr:
                    module_root = mr.group(1)
                kind = "FILE"
                if target is None:
                    kind = "UNRESOLVED"
                elif body and body[0].startswith("package "):
                    kind = "FILE"                      # a complete Java/Kotlin source file, whatever the caption says
                elif PARTIAL.search(rest) or rest.strip().lower().startswith(("in ", "to ")):
                    kind = "SNIPPET"
                results.append((kind, target or path_text, "\n".join(body) + "\n", i + 1, fence_lang))
            last_caption = None
            i = j + 1
            continue
        c = CAPTION.match(line.strip())
        if c and FILE_LIKE.search(c.group(1)):
            # remember the caption; it applies if the next non-blank line opens a code block
            k = i + 1
            while k < len(lines) and not lines[k].strip():
                k += 1
            if k < len(lines) and lines[k].startswith("```"):
                last_caption = (c.group(1), c.group(2), k)
        i += 1
    return results


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("chapter", help="Markdown chapter file")
    ap.add_argument("--write", action="store_true", help="actually write files (default: dry run)")
    ap.add_argument("--force", action="store_true", help="overwrite existing files")
    ap.add_argument("--root", default=".", help="repo root (default: current directory)")
    a = ap.parse_args()

    with open(a.chapter, encoding="utf-8") as f:
        items = extract(f.read())

    written = skipped = snippets = 0
    for kind, target, body, line_no, _ in items:
        if kind != "FILE":
            snippets += 1
            print(f"  {kind:10} line {line_no:5}  {target}   (apply by hand)")
            continue
        dest = os.path.join(a.root, target)
        exists = os.path.exists(dest)
        if exists and not a.force:
            skipped += 1
            print(f"  EXISTS     line {line_no:5}  {target}   (use --force to overwrite)")
            continue
        print(f"  {'WRITE' if a.write else 'would write':10} line {line_no:5}  {target}")
        if a.write:
            os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
            with open(dest, "w", encoding="utf-8", newline="\n") as f:
                f.write(body)
            if target.endswith(".sh"):
                os.chmod(dest, 0o755)
            written += 1

    print(f"\n{len(items)} captioned blocks: {written} written, {skipped} existing (skipped), {snippets} snippets/unresolved (manual)")
    if not a.write:
        print("Dry run only. Re-run with --write to create the files.")


if __name__ == "__main__":
    sys.exit(main())
