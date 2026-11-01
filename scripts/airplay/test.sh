#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Desktop tests for the AirPlay receiver (android/app/src/main/java/com/cleanframe/firetv/airplay).
# Runs the pure-Java core outside Android against independent references:
#   - FairPlay (playfair) against vectors from the original C code (fixtures/playfair-vectors.bin)
#   - Ed25519/X25519 against RFC 8032/7748 and the JDK's own implementations (JDK 15 or later)
#   - binary plists against Python's plistlib (if python3 is installed)
#   - a fake iPhone session end to end: pairing, FairPlay, SETUP, an encrypted H.264 mirror
#     stream (JDK AES-CTR), triple-sent audio with a resend, volume, teardown, takeover, code
#   - Apple Lossless against FFmpeg's decoder (if ffmpeg is installed)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
src="$repo/android/app/src/main/java/com/cleanframe/firetv/airplay"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"

# The Android glue (decoders, service, screen) needs the Android SDK; everything else is plain Java.
core=$(grep -L '^import android\.' "$src"/*.java)
javac -nowarn -d "$work/classes" $core "$here"/tests/*.java
run() { java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$work/classes" "com.cleanframe.firetv.airplay.$@"; }

echo "# FairPlay"
run PlayfairDiffTest "$repo/android/app/src/main/assets/airplay/playfair.bin" "$here/fixtures/playfair-vectors.bin"
echo "# Curve25519"
run CurveTest 40
if command -v python3 >/dev/null; then
  echo "# Binary plists"
  mkdir -p "$work/plist"
  run BPlistTest write "$work/plist" >/dev/null
  python3 -I -c '
import plistlib, sys
d = plistlib.loads(open(sys.argv[1] + "/java.bplist", "rb").read())
assert d["name"] == "Jasons Fire TV – Wohnzimmer" and d["features"] == 0x5A7FFEE6 | (1 << 40) and d["displays"][0]["huge"] == 5000000000
assert len(d["blob"]) == 70000 and d["many"][299] == 299000 and d["keepAliveLowPower"] is True
open(sys.argv[1] + "/python.bplist", "wb").write(plistlib.dumps({"deviceID": "11:22:33:44:55:66", "name": "Jason’s iPhone", "ekey": bytes(72),
  "eiv": bytes(15) + b"\x39", "timingPort": 57345, "isScreenMirroringSession": True, "streams": [{"type": 110, "streamConnectionID": -5}],
  "neg": -3, "real": 0.5}, fmt=plistlib.FMT_BINARY))
print("ok - Python reads the Java plist")' "$work/plist"
  got=$(run BPlistTest read "$work/plist")
  want="11:22:33:44:55:66|Jason’s iPhone|72|57|57345|true|110|-5|-3|0.5"
  if [ "$got" = "$want" ]; then echo "ok - Java reads the Python plist"; else echo "FAIL - Java reads the Python plist: $got"; exit 1; fi
fi
echo "# A fake iPhone"
run EndToEndTest "$repo/android/app/src/main/assets/airplay/playfair.bin" "$here/fixtures" "$work"
if command -v ffmpeg >/dev/null && command -v python3 >/dev/null; then
  echo "# Apple Lossless"
  a="$work/alac"; mkdir -p "$a"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=2" -f lavfi -i "anoisesrc=color=pink:amplitude=0.3:sample_rate=44100:duration=2" \
    -filter_complex "[0][1]amix=inputs=2,volume=0.8[l];[1]volume=0.6[r];[l][r]join=inputs=2:channel_layout=stereo,aformat=sample_fmts=s16" -c:a alac "$a/music.caf"
  ffmpeg -hide_banner -loglevel error -y -i "$a/music.caf" -c:a alac -compression_level 0 "$a/level0.caf"
  ffmpeg -hide_banner -loglevel error -y -f lavfi -i "anoisesrc=color=white:amplitude=1.0:sample_rate=44100:duration=1" -ac 2 -sample_fmt s16p -c:a alac "$a/noise.caf"
  for n in music level0 noise; do
    python3 -I "$here/alac_packets.py" "$a/$n.caf" "$a/$n.packets" >/dev/null
    ffmpeg -hide_banner -loglevel error -y -i "$a/$n.caf" -f s16le -ac 2 "$a/$n.pcm"
  done
  run AlacTest "$a/music" "$a/level0" "$a/noise"
else
  echo "skip - Apple Lossless (needs ffmpeg and python3)"
fi
echo "All AirPlay tests passed."
