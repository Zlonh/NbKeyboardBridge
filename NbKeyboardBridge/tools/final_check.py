"""Final structural self-check for the NbKeyboardBridge project.

Run from the workspace root:
    python final_check.py
"""

import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
JAVA = os.path.join(ROOT, "app", "src", "main", "java")

failures = []
notes = []


def fail(msg):
    failures.append(msg)


# ---------------------------------------------------------------- 1. XML validity
xml_count = 0
for base, _, files in os.walk(ROOT):
    for fn in files:
        if fn.endswith(".xml"):
            xml_count += 1
            path = os.path.join(base, fn)
            try:
                ET.parse(path)
            except ET.ParseError as exc:
                fail(f"malformed XML: {os.path.relpath(path, ROOT)} -> {exc}")
notes.append(f"XML files parsed: {xml_count}")

# ---------------------------------------------------------------- 2. resource refs
defined = defaultdict(set)
for base, _, files in os.walk(RES):
    folder = os.path.basename(base)
    for fn in files:
        name, ext = os.path.splitext(fn)
        path = os.path.join(base, fn)
        if folder.startswith("values") and ext == ".xml":
            text = open(path, encoding="utf-8").read()
            for kind, nm in re.findall(
                r"<(string|color|style|dimen|integer|bool|string-array|array|plurals)\s+name=\"([^\"]+)\"",
                text,
            ):
                defined[kind].add(nm)
        elif folder.startswith(("layout", "drawable", "mipmap", "color", "menu", "xml", "anim")):
            defined[folder.split("-")[0]].add(name)

sources = []
for base, _, files in os.walk(RES):
    for fn in files:
        if fn.endswith(".xml"):
            sources.append(os.path.join(base, fn))
for base, _, files in os.walk(JAVA):
    for fn in files:
        if fn.endswith((".kt", ".java")):
            sources.append(os.path.join(base, fn))

TYPES = ["drawable", "string", "color", "style", "layout", "mipmap", "id",
         "dimen", "integer", "bool", "array", "plurals", "menu", "xml", "anim"]
defined["id"] = set()
for path in sources:
    text = open(path, encoding="utf-8", errors="replace").read()
    defined["id"].update(re.findall(r"@\+id/([A-Za-z_][A-Za-z0-9_]*)", text))

# dotted style names (Widget.Nb.Card) are valid; the regex only catches the head
DOTTED_OK = {"TextAppearance", "Widget", "Theme"}
missing_refs = []
for path in sources:
    text = open(path, encoding="utf-8", errors="replace").read()
    for t in TYPES:
        for m in re.finditer(r"(?<![\w@])@(?!android:)" + t + r"/([A-Za-z_][A-Za-z0-9_]*)", text):
            nm = m.group(1)
            if nm in DOTTED_OK:
                continue
            if nm not in defined.get(t, set()):
                missing_refs.append(f"@{t}/{nm} in {os.path.relpath(path, ROOT)}")
for r in sorted(set(missing_refs)):
    fail("unresolved resource reference: " + r)
notes.append(f"resource types defined: {', '.join(sorted(k for k in defined if defined[k]))}")

# ---------------------------------------------------------------- 3. viewbinding ids
layout_ids = set()
for base, _, files in os.walk(RES):
    if not os.path.basename(base).startswith("layout"):
        continue
    for fn in files:
        layout_ids.update(re.findall(
            r'android:id="@\+id/([A-Za-z_][A-Za-z0-9_]*)"',
            open(os.path.join(base, fn), encoding="utf-8").read()))


def camel(snake):
    parts = snake.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


available_props = {camel(i) for i in layout_ids}
code = ""
for base, _, files in os.walk(JAVA):
    for fn in files:
        if fn.endswith(".kt"):
            code += open(os.path.join(base, fn), encoding="utf-8").read()
used_props = set(re.findall(r"binding\.([A-Za-z_][A-Za-z0-9_]*)", code))
BINDING_CLASSES = {"ActivityMainBinding", "DialogSingleKeyBinding",
                   "ItemDeviceBinding", "ItemSingleKeyBinding", "Root"}
for prop in sorted(used_props):
    if prop in BINDING_CLASSES or prop in ("root", "adapter", "layoutManager", "itemBinding"):
        continue
    if prop not in available_props:
        fail(f"binding.{prop} has no matching android:id in any layout")
notes.append(f"binding properties used: {len(used_props)} / available: {len(available_props)}")

# ---------------------------------------------------------------- 4. imports
for base, _, files in os.walk(JAVA):
    for fn in files:
        if not fn.endswith(".kt"):
            continue
        path = os.path.join(base, fn)
        lines = open(path, encoding="utf-8").read().split("\n")
        body = "\n".join(l for l in lines if not l.strip().startswith("import "))
        for i, line in enumerate(lines):
            m = re.match(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", line.strip())
            if not m:
                continue
            name = m.group(2) or m.group(1).split(".")[-1]
            if not re.search(r"\b" + re.escape(name) + r"\b", body):
                notes.append(f"unused import {m.group(1)} ({os.path.relpath(path, ROOT)}:{i + 1})")

# ---------------------------------------------------------------- 5. string parity
def strings_of(folder):
    path = os.path.join(RES, folder, "strings.xml")
    if not os.path.exists(path):
        return {}
    return {el.get("name"): "".join(el.itertext())
            for el in ET.parse(path).getroot() if el.tag == "string"}


zh, en = strings_of("values"), strings_of("values-en")
if set(zh) != set(en):
    fail(f"values-en/strings.xml key mismatch: missing={sorted(set(zh) - set(en))} "
         f"extra={sorted(set(en) - set(zh))}")
for k in set(zh) & set(en):
    a = sorted(re.findall(r"%\d+\$[sd]", zh[k]))
    b = sorted(re.findall(r"%\d+\$[sd]", en[k]))
    if a != b:
        fail(f"placeholder mismatch for string '{k}': zh={a} en={b}")
notes.append(f"strings: default={len(zh)} en={len(en)}")

# ---------------------------------------------------------------- 6. Kotlin sanity
kt_files = [os.path.join(b, f) for b, _, fs in os.walk(JAVA) for f in fs if f.endswith(".kt")]
for path in kt_files:
    text = open(path, encoding="utf-8").read()
    if not text.startswith("package "):
        fail(f"missing package declaration: {os.path.relpath(path, ROOT)}")
    depth = 0
    for ch in text:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        if depth < 0:
            fail(f"unbalanced braces: {os.path.relpath(path, ROOT)}")
            break
    if depth != 0:
        fail(f"unbalanced braces (final depth {depth}): {os.path.relpath(path, ROOT)}")
notes.append(f"kotlin files: {len(kt_files)}")

# ---------------------------------------------------------------- 7. manifest classes
manifest = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
mf = open(manifest, encoding="utf-8").read()
pkg = re.search(r'package="([^"]+)"', mf)
namespace = re.search(r'namespace\s*=\s*"([^"]+)"',
                      open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read())
ns = (namespace.group(1) if namespace else (pkg.group(1) if pkg else ""))
for cls in re.findall(r'android:name="\.([\w.]+)"', mf):
    rel = os.path.join(JAVA, *ns.split("."), *cls.split(".")) + ".kt"
    if not os.path.exists(rel):
        fail(f"manifest references .{cls} but {os.path.relpath(rel, ROOT)} does not exist")
notes.append(f"namespace: {ns}")

# ---------------------------------------------------------------- report
print("=" * 62)
print("FINAL STRUCTURAL CHECK")
print("=" * 62)
for n in notes:
    print("  -", n)
print()
if failures:
    print(f"FAILURES ({len(failures)}):")
    for f in failures:
        print("  x", f)
    sys.exit(1)
print("RESULT: no structural problems found.")
