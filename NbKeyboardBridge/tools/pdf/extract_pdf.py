#!/usr/bin/env python3
"""Extract text and embedded images from a PDF using only the standard library.

Written because this project's build environment has no PDF library (no pypdf,
pdfplumber, fitz...) and no network access to install one. It handles the common
case well enough to recover a specification document:

  * FlateDecode content streams (the usual case for Word / Writer exports)
  * Type0 / Identity-H fonts with a /ToUnicode CMap -> real Unicode text
  * simple WinAnsi text
  * image XObjects (DeviceRGB / DeviceGray, 8bpc) -> PNG via Pillow
  * object streams (/ObjStm), so modern PDFs are readable too

Usage:
    python extract_pdf.py INPUT.pdf [-o OUTDIR]

Outputs into OUTDIR (default: ./pdf_extract):
    text.txt              per-page text with positions
    images/*.png          embedded images, numbered by page
    structure.txt         object-by-object dump, for when text extraction fails
"""

import argparse
import os
import re
import sys
import zlib


# --------------------------------------------------------------------------- parsing

def inflate(body: bytes):
    """Return the decoded stream of a PDF object body, or None."""
    m = re.search(rb"stream\r?\n", body)
    if not m:
        return None
    end = body.find(b"endstream", m.end())
    if end < 0:
        end = len(body)
    raw = body[m.end():end]
    header = body[:m.start()]
    if b"FlateDecode" in header:
        try:
            return zlib.decompress(raw)
        except zlib.error:
            try:
                return zlib.decompressobj().decompress(raw)
            except zlib.error:
                return None
    return raw


def load_objects(data: bytes):
    """Map object number -> body bytes, expanding /ObjStm containers."""
    objs = {}
    for m in re.finditer(rb"(\d+)\s+(\d+)\s+obj(.*?)endobj", data, re.S):
        objs[int(m.group(1))] = m.group(3)

    for num in list(objs):
        body = objs[num]
        if b"/ObjStm" not in body:
            continue
        dec = inflate(body)
        if dec is None:
            continue
        try:
            first = int(re.search(rb"/First (\d+)", body).group(1))
            count = int(re.search(rb"/N (\d+)", body).group(1))
        except AttributeError:
            continue
        header = dec[:first].split()
        for i in range(count):
            try:
                onum = int(header[2 * i])
                off = int(header[2 * i + 1])
            except (IndexError, ValueError):
                break
            if i + 1 < count:
                nxt = int(header[2 * i + 3])
            else:
                nxt = len(dec) - first
            objs[onum] = dec[first + off:first + nxt]
    return objs


def parse_tounicode(stream: bytes):
    """Parse a /ToUnicode CMap into {cid: char}."""
    mapping = {}
    text = stream.decode("latin-1")
    for block in re.findall(r"beginbfchar(.*?)endbfchar", text, re.S):
        for src, dst in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            try:
                mapping[int(src, 16)] = "".join(
                    chr(int(dst[i:i + 4], 16)) for i in range(0, len(dst), 4)
                )
            except ValueError:
                pass
    for block in re.findall(r"beginbfrange(.*?)endbfrange", text, re.S):
        for lo, hi, dst in re.findall(
            r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block
        ):
            try:
                base = int(dst, 16)
                for k in range(int(lo, 16), int(hi, 16) + 1):
                    mapping[k] = chr(base + (k - int(lo, 16)))
            except ValueError:
                pass
    return mapping


def page_objects(objs):
    """Ordered list of (page_obj_num, content_obj_num) using the page tree."""
    kids = []
    for num in sorted(objs):
        body = objs[num]
        if re.search(rb"/Type\s*/Page[^s]", body):
            m = re.search(rb"/Contents (\d+) 0 R", body)
            kids.append((num, int(m.group(1)) if m else None))
    return kids


# --------------------------------------------------------------------------- text

def extract_text(objs, cmap):
    lines = []
    for page_no, (pobj, cobj) in enumerate(page_objects(objs), 1):
        lines.append(f"===== PAGE {page_no} (object {pobj}) =====")
        if cobj is None or cobj not in objs:
            lines.append("  (no content stream)")
            continue
        dec = inflate(objs[cobj])
        if dec is None:
            lines.append("  (undecodable content stream)")
            continue
        content = dec.decode("latin-1")
        for block in re.findall(r"BT(.*?)ET", content, re.S):
            tm = re.search(r"1 0 0 1 ([\d.\-]+) ([\d.\-]+) Tm", block)
            pos = f"({tm.group(1)},{tm.group(2)})" if tm else "(?)"
            font = re.search(r"/(F\d+) [\d.]+ Tf", block)
            fname = font.group(1) if font else "?"
            for arr in re.findall(r"\[(.*?)\]\s*TJ", block, re.S):
                if "<" in arr:
                    out = []
                    for hx in re.findall(r"<([0-9A-Fa-f]+)>", arr):
                        hx = hx.zfill(4)
                        for i in range(0, len(hx), 4):
                            cid = int(hx[i:i + 4], 16)
                            out.append(cmap.get(cid, f"[{cid:04X}]"))
                    lines.append(f"  {pos} {fname}: {''.join(out)}")
                else:
                    for s in re.findall(r"\((.*?)(?<!\\)\)", arr, re.S):
                        lines.append(f"  {pos} {fname}: {s}")
    return "\n".join(lines)


# --------------------------------------------------------------------------- images

def extract_images(objs, outdir):
    try:
        from PIL import Image
    except ImportError:
        return ["Pillow not installed - skipped image extraction"]

    os.makedirs(outdir, exist_ok=True)
    notes = []
    for num in sorted(objs):
        body = objs[num]
        if b"/Subtype/Image" not in body:
            continue
        def grab(pattern, cast=int, default=None):
            m = re.search(pattern, body)
            return cast(m.group(1)) if m else default

        width = grab(rb"/Width (\d+)")
        height = grab(rb"/Height (\d+)")
        bpc = grab(rb"/BitsPerComponent (\d+)", default=8)
        cs = grab(rb"/ColorSpace/(\w+)", cast=lambda b: b.decode(), default="DeviceRGB")
        if not width or not height or bpc != 8 or cs not in ("DeviceRGB", "DeviceGray"):
            notes.append(f"object {num}: unsupported ({width}x{height} {cs} {bpc}bpc)")
            continue
        raw = inflate(body)
        if raw is None:
            notes.append(f"object {num}: undecodable stream")
            continue
        mode = "RGB" if cs == "DeviceRGB" else "L"
        expected = width * height * (3 if mode == "RGB" else 1)
        if len(raw) != expected:
            notes.append(f"object {num}: size mismatch {len(raw)} != {expected}")
            continue
        img = Image.frombytes(mode, (width, height), raw)
        path = os.path.join(outdir, f"obj{num}_{width}x{height}.png")
        img.save(path)
        notes.append(f"object {num}: {path}")
    return notes


# --------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pdf")
    ap.add_argument("-o", "--outdir", default="pdf_extract")
    args = ap.parse_args()

    data = open(args.pdf, "rb").read()
    os.makedirs(args.outdir, exist_ok=True)

    objs = load_objects(data)
    print(f"parsed {len(objs)} objects from {args.pdf}")

    cmap = {}
    for num, body in objs.items():
        if b"/ToUnicode" in body:
            m = re.search(rb"/ToUnicode (\d+) 0 R", body)
            if m:
                target = objs.get(int(m.group(1)))
                if target is not None:
                    stream = inflate(target)
                    if stream:
                        cmap.update(parse_tounicode(stream))
    print(f"ToUnicode entries: {len(cmap)}")

    text = extract_text(objs, cmap)
    text_path = os.path.join(args.outdir, "text.txt")
    open(text_path, "w", encoding="utf-8").write(text)
    print(f"text  -> {text_path}")

    notes = extract_images(objs, os.path.join(args.outdir, "images"))
    for n in notes:
        print("image ->", n)

    struct_path = os.path.join(args.outdir, "structure.txt")
    with open(struct_path, "w", encoding="utf-8") as fh:
        for num in sorted(objs):
            head = re.sub(rb"\s+", b" ", objs[num][:300])
            fh.write(f"--- OBJ {num}: {head[:260]!r}\n")
    print(f"structure -> {struct_path}")

    if not text.strip():
        print("\nNOTE: no text extracted - this PDF is probably scanned images.",
              file=sys.stderr)
        print("      Read the PNGs in images/ instead.", file=sys.stderr)


if __name__ == "__main__":
    main()
