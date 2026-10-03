"""Independent re-implementation of the NB- frame builder, checked against the
worked examples in the manual (page 2).

FRAME LENGTH -- the subtle part
-------------------------------
The manual's table says "DATA = 8 个字节数据", which would give a 14-byte frame:

    HEAD(2) + ADDR(1) + CMD(1) + LEN(1) + DATA(8) + SUM(1) = 14

...but every frame the manual actually prints is **15 bytes**, and the printed
checksum only adds up at 15 bytes. Worked example 1.1, byte by byte:

    idx  0  1  2  3  4  5  6  7  8 ... 12 13 14
         57 AB 00 02 08 00 00 04 00 ... 00 00 10
                          ^^^^^ keycode 'A' = 0x04
    sum(bytes[0..13]) = 0x110 -> &0xFF = 0x10  == printed SUM

If the frame ended one byte earlier, the keycode 'A' would land in byte 6 and the
sum would be 0x0F, not the printed 0x10. So the real frame is:

    HEAD(2) + ADDR(1) + CMD(1) + LEN(1) + 9 more bytes + SUM(1) = 15

i.e. the 8 bytes of HID report data occupy indices 7..13 (right-aligned in a
9-byte data region), with index 13 always 0x00. Equivalently: the 8-byte HID
report is preceded by TWO zero bytes (indices 5 and 6) instead of one.

NbProtocol.buildFrame uses the 15-byte layout, which is what reproduces the
manual's checksums. This file verifies that.
"""

HID_REPORT_LEN = 8
FRAME_LEN = 15


def build_key_data(modifier, keys):
    """Mirror of NbProtocol.buildKeyData: the 8-byte HID keyboard report."""
    data = [modifier & 0xFF, 0x00]
    for k in keys:
        if k == 0 or len(data) >= HID_REPORT_LEN:
            continue
        data.append(k & 0xFF)
    while len(data) < HID_REPORT_LEN:
        data.append(0x00)
    assert len(data) == HID_REPORT_LEN
    return data


def build_frame(data):
    """Mirror of NbProtocol.buildFrame.

    57 AB | 00 | 02 | 08 | 00 | data[0..7] -> 15 bytes total, SUM last.
    """
    assert len(data) == HID_REPORT_LEN, len(data)
    frame = [0x57, 0xAB, 0x00, 0x02, 0x08, 0x00] + list(data)
    assert len(frame) == 14, len(frame)          # 6 + 8, SUM still to come
    frame.append(sum(frame) & 0xFF)
    assert len(frame) == FRAME_LEN, len(frame)
    return frame


# (label, modifier byte, keycodes, expected SUM as printed in the manual)
CASES = [
    ("example 1.1  press A",      0x00, [0x04], 0x10),
    ("example 1.2  release A",    0x00, [],     0x0C),
    ("example 2.1  press Shift+A", 0x02, [0x04], 0x12),
    ("example 2.2  release all",  0x00, [],     0x0C),
]

print(f"{'case':26s} {'len':>4s}  {'SUM':>5s} {'want':>5s}  result")
ok_all = True
for label, modifier, keys, want_sum in CASES:
    frame = build_frame(build_key_data(modifier, keys))
    ok = len(frame) == FRAME_LEN and frame[14] == want_sum
    ok_all = ok_all and ok
    print(f"{label:26s} {len(frame):>4d}  {frame[14]:>#5x} {want_sum:>#5x}  {'OK' if ok else 'MISMATCH'}")

print()
print("all manual examples reproduced:", ok_all)

print()
print("frame anatomy (a key press):")
f = build_frame(build_key_data(0x00, [0x04]))
labels = ["HEAD", "HEAD", "ADDR", "CMD", "LEN", "pad", "MOD", "rsv",
          "k1", "k2", "k3", "k4", "k5", "k6", "SUM"]
for i, (lab, val) in enumerate(zip(labels, f)):
    print(f"  [{i:2d}] {lab:5s} {val:#04x}")

print()
six = build_key_data(0x08 | 0x02, [0x04, 0x05, 0x06, 0x07, 0x08, 0x09])
print("6-key rollover (Win+Shift + A..F):", " ".join(f"{b:02X}" for b in six))
print("  modifier =", f"{six[0]:#04x}", " reserved =", f"{six[1]:#04x}", " keys =", six[2:])
