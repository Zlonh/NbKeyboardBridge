"""Ground-truth check: every 15-byte frame printed in the manual must satisfy
SUM == sum(bytes[0..13]) & 0xFF.

The frames are taken verbatim from the manual page-2 images:
  press A      57 AB 00 02 08 00 00 04 00 00 00 00 00 00 10
  release A    57 AB 00 02 08 00 00 00 00 00 00 00 00 00 0C
  press Shift+A 57 AB 00 02 08 02 00 04 00 00 00 00 00 00 12
"""

FRAMES = {
    "press_A":   "57 AB 00 02 08 00 00 04 00 00 00 00 00 00 10",
    "release_A": "57 AB 00 02 08 00 00 00 00 00 00 00 00 00 0C",
    "press_SA":  "57 AB 00 02 08 02 00 04 00 00 00 00 00 00 12",
}

print(f"{'name':11s} {'len':>4s} {'bytes[0..13] sum':>18s} {'&0xFF':>6s} {'SUM byte':>9s}  result")
all_ok = True
for name, text in FRAMES.items():
    b = [int(x, 16) for x in text.split()]
    body = b[:14]
    s = sum(body)
    low = s & 0xFF
    ok = len(b) == 15 and low == b[14]
    all_ok = all_ok and ok
    print(f"{name:11s} {len(b):>4d} {s:>18d} {low:>#6x} {b[14]:>#9x}  {'OK' if ok else 'MISMATCH'}")

print()
print("checksum rule  SUM = sum(bytes[0..13]) & 0xFF  reproduces all manual frames:", all_ok)

# Also confirm the 8 data bytes fall where the frame layout says they do
print()
print("frame layout check")
for name, text in FRAMES.items():
    b = [int(x, 16) for x in text.split()]
    print(f"  {name:11s} HDR={b[0]:02X} {b[1]:02X}  ADDR={b[2]:02X}  CMD={b[3]:02X}  "
          f"LEN={b[4]:02X}  DATA={' '.join(f'{x:02X}' for x in b[5:13])}  SUM={b[14]:02X}")
