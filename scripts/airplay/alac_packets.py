# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes the packets of an ALAC file (from ffprobe) as [length][bytes] records after the codec cookie."""
import json, subprocess, sys, struct
src, out = sys.argv[1], sys.argv[2]
def hexdump_to_bytes(s):
    b = bytearray()
    for line in s.strip().split('\n'):
        b += bytes.fromhex(line[10:49].replace(' ', ''))
    return bytes(b)
st = json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_data','-of','json',src]))['streams'][0]
pk = json.loads(subprocess.check_output(['ffprobe','-v','error','-show_packets','-show_data','-of','json',src]))['packets']
cookie = hexdump_to_bytes(st['extradata'])
with open(out,'wb') as f:
    f.write(struct.pack('>I', len(cookie))); f.write(cookie)
    for p in pk:
        d = hexdump_to_bytes(p['data']); assert len(d) == int(p['size'])
        f.write(struct.pack('>I', len(d))); f.write(d)
print(src, len(pk), 'packets, cookie', cookie.hex())
