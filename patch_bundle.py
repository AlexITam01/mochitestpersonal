#!/usr/bin/env python3
"""patch_bundle.py — apply the Mochi blank-screen fix to a stock Hermes v98 bundle.

The stock bundle's getMySeriesCreationDraft throws
Error("getMySeriesCreationDraft returned no data") when the server returns no
draft data; the screen has no error UI, so it renders blank. This patch
neutralizes the throw so the function returns an empty object {} instead.

Same-length byte replacement only (28 bytes across two ranges). The bundle
size is unchanged.

Usage:
    python3 patch_bundle.py <input.bundle> <output.bundle>
"""
import hashlib
import sys

# (offset, original_hex, replacement_hex)
PATCHES = [
    (6730527,
     "3d05480805010a0086070802900bc518100c076b",
     "04071002063901030601050c0944c15205070076"),
    (6730548,
     "0802880507057905",
     "0413041304130413"),
]

EXPECTED_MD5_ORIG = "a8f2e3dccc905defd76b86b35d7c1b74"
EXPECTED_MD5_PATCHED = "78f5eaa415c3142e5da62f6ef0339c03"


def main():
    src, dst = sys.argv[1], sys.argv[2]
    data = bytearray(open(src, "rb").read())
    print(f"input: {src} ({len(data)} bytes, md5 {hashlib.md5(data).hexdigest()})")
    if hashlib.md5(data).hexdigest() != EXPECTED_MD5_ORIG:
        print("WARNING: input MD5 does not match the stock 1.1.11 bundle; "
              "offsets may be wrong. Aborting.", file=sys.stderr)
        sys.exit(1)
    for offset, orig_hex, new_hex in PATCHES:
        orig = bytes.fromhex(orig_hex)
        new = bytes.fromhex(new_hex)
        assert len(orig) == len(new), "patch must be same-length"
        if bytes(data[offset:offset + len(orig)]) != orig:
            print(f"ERROR: bytes at offset {offset} do not match expected "
                  f"original; bundle differs. Aborting.", file=sys.stderr)
            sys.exit(1)
        data[offset:offset + len(new)] = new
    open(dst, "wb").write(bytes(data))
    md5 = hashlib.md5(bytes(data)).hexdigest()
    print(f"patched: {dst} ({len(data)} bytes, md5 {md5})")
    if md5 != EXPECTED_MD5_PATCHED:
        print("ERROR: patched MD5 mismatch.", file=sys.stderr)
        sys.exit(1)
    print("OK — patched bundle MD5 matches.")


if __name__ == "__main__":
    main()
