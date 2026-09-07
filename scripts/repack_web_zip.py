#!/usr/bin/env python3
"""Repack web/dist into web-0.0.1.zip using forward-slash entries only.

The device-side ZipInputStream is sensitive to backslashes; zipfile on Windows
would otherwise emit '\\' separators. We build from dist/ so the archive mirrors
the served bundle (root index.html / favicon.svg / version.json + assets/...).
"""
import os
import zipfile

ROOT = r"D:\AndroidStudioProjects\new\UFI-AXIS"
DIST = os.path.join(ROOT, "web", "dist")
OUT = os.path.join(ROOT, "web-0.0.1.zip")

if not os.path.isdir(DIST):
    raise SystemExit(f"dist not found: {DIST}")

# Build fresh archive (overwrite existing).
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as zf:
    added = 0
    for dirpath, _dirnames, filenames in os.walk(DIST):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            # Relative to dist, always forward slashes.
            rel = os.path.relpath(full, DIST).replace(os.sep, "/")
            zf.write(full, rel)
            added += 1

# Verify no backslash anywhere in the entry names.
with zipfile.ZipFile(OUT, "r") as zf:
    names = zf.namelist()
    backslash = [n for n in names if "\\" in n]

print(f"entries={len(names)}")
print(f"has_index_html={'index.html' in names}")
print(f"has_version_json={'version.json' in names}")
print(f"backslash_entries={len(backslash)}")
if backslash:
    print("BACKSLASH FOUND:", backslash[:10])
    raise SystemExit("zip contains backslash entries")
print("OK: all entries use forward slashes")
