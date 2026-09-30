"""Inspect 64-bit ELF load-segment alignment inside an Android bundle (no extraction)."""
import json, struct, sys, zipfile
rows = []
with zipfile.ZipFile(sys.argv[1]) as bundle:
    for name in bundle.namelist():
        if '/lib/' not in name or not name.endswith('.so'):
            continue
        data = bundle.read(name)
        if data[:4] != b'\x7fELF' or data[4] != 2:
            continue
        endian = '<' if data[5] == 1 else '>'
        phoff = struct.unpack_from(endian + 'Q', data, 32)[0]
        entsize, count = struct.unpack_from(endian + 'HH', data, 54)
        alignments = []
        valid = True
        for i in range(count):
            offset = phoff + i * entsize
            if struct.unpack_from(endian + 'I', data, offset)[0] != 1:
                continue
            file_offset, virtual = struct.unpack_from(endian + 'QQ', data, offset + 8)
            alignment = struct.unpack_from(endian + 'Q', data, offset + 48)[0]
            alignments.append(alignment)
            valid &= alignment >= 16384 and file_offset % 16384 == virtual % 16384
        rows.append({'library': name, 'load_alignments': alignments, 'compatible_16k': bool(alignments) and valid})
print(json.dumps(rows, indent=2))
if not rows or any(not row['compatible_16k'] for row in rows):
    sys.exit(1)
