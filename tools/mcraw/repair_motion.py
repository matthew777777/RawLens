#!/usr/bin/env python3
"""Repair a pre-fix .mcraw so motioncam-decoder tooling plays it with audio.

Two defects, one rewrite (frames, audio and motion *samples* preserved):

1. Motion chunks exceed frames + 1 (one chunk per sensor drain, ~100/s):
   motioncam-decoder throws "Invalid gyro index" and aborts the whole open.
   Fixed by coalescing each sensor to a single chunk.
2. Absolute boot-time timestamps: readers do 32-bit ms math that they
   overflow, breaking audio sync entirely (PhotonCamera's documented fix).
   Fixed by rebasing every timestamp to the first frame's, dropping audio
   chunks and motion samples from before it (pre-roll without video).

Usage: repair_motion.py [--stereo] INPUT.mcraw OUTPUT.mcraw

With --stereo, mono audio is additionally duplicated to L=R stereo and
extraData.audioChannels is set to 2 (refuses non-mono input, so stereo
can never be doubled to 4 channels by accident).

Only stdlib. Streams payloads (constant memory). Refuses to overwrite: the
output path must not exist. Never modifies the input. Idempotent: an
already-relative file with coalesced motion round-trips semantically
unchanged (frame metadata is re-serialized compactly).
"""
import json
import struct
import sys

T_INDEX, T_INDEX_DATA, T_FRAME, T_META = 0, 1, 2, 3
T_AUDIO_INDEX, T_AUDIO_DATA, T_AUDIO_META = 4, 5, 6
T_GYRO_INDEX, T_GYRO_DATA = 8, 9
T_ACCEL_INDEX, T_ACCEL_DATA = 12, 13
FOOTER_MAGIC = 0x8A905612
COPY = 1 << 20


def die(msg):
    sys.exit("repair_motion: " + msg)


def read_exact(f, n, what):
    b = f.read(n)
    if len(b) != n:
        die("truncated %s" % what)
    return b


def copy_bytes(fin, fout, n):
    while n > 0:
        b = fin.read(min(COPY, n))
        if not b:
            die("truncated payload")
        fout.write(b)
        n -= len(b)


def rebase_frame_meta(payload, origin):
    """Rebase timestamp/filename/metadataTimestamp by origin, compact JSON."""
    obj = json.loads(payload.decode("utf-8"))
    for key in ("timestamp", "filename", "metadataTimestamp"):
        if key not in obj:
            continue
        val = obj[key]
        try:
            num = int(val)
        except (TypeError, ValueError):
            continue  # absent/empty in some writers; leave alone
        rel = num - origin
        if rel < 0:
            die("frame metadata timestamp predates origin")
        obj[key] = str(rel) if isinstance(val, str) else rel
    return json.dumps(obj, separators=(",", ":")).encode("utf-8")


def main():
    args = sys.argv[1:]
    stereo = "--stereo" in args
    args = [a for a in args if a != "--stereo"]
    if len(args) != 2:
        die("usage: repair_motion.py [--stereo] INPUT.mcraw OUTPUT.mcraw")
    src, dst = args
    try:
        fout = open(dst, "xb")
    except FileExistsError:
        die("output exists, refusing to overwrite: " + dst)
    with fout:
        with open(src, "rb") as fin:
            fin.seek(0, 2)
            size = fin.tell()
            if size < 32:
                die("file too small")
            fin.seek(0)
            if read_exact(fin, 8, "header") != b"MOTION \x03":
                die("bad magic/version")
            # Container metadata: verbatim, except --stereo flips channels.
            t, n = struct.unpack("<II", read_exact(fin, 8, "meta head"))
            if t != T_META:
                die("missing container metadata")
            meta_in = read_exact(fin, n, "meta")
            fout.write(b"MOTION \x03")
            if stereo:
                meta = json.loads(meta_in.decode("utf-8"))
                try:
                    ch = meta["extraData"]["audioChannels"]
                except KeyError:
                    die("no extraData.audioChannels")
                if ch != 1:
                    die("--stereo needs mono input (audioChannels=%r)" % (ch,))
                meta["extraData"]["audioChannels"] = 2
                payload = json.dumps(meta, separators=(",", ":")).encode("utf-8")
                fout.write(struct.pack("<II", t, len(payload)))
                fout.write(payload)
            else:
                fout.write(struct.pack("<II", t, n))
                fout.write(meta_in)
            # Footer + original frame index: source of frame timestamps.
            fin.seek(size - 24)
            t, n, magic, count, index_off = struct.unpack(
                "<IIIIq", read_exact(fin, 24, "footer"))
            if t != T_INDEX or n != 16 or magic != FOOTER_MAGIC or count <= 0:
                die("bad footer")
            fin.seek(index_off - 8)
            t, n = struct.unpack("<II", read_exact(fin, 8, "frame index head"))
            if t != T_INDEX_DATA or n != count * 16:
                die("bad frame index item")
            frame_ts = {}  # old offset -> timestamp, in index order
            order = []
            for _ in range(count):
                o, ts = struct.unpack("<qq", read_exact(fin, 16, "frame entry"))
                frame_ts[o] = ts
                order.append(o)
            # Recording origin: first frame's timestamp (min: robust to any
            # index order, and 0 for an already-relative file -> no-op).
            origin = min(frame_ts.values())
            # Pass 1: walk the data region, cataloguing items.
            items = []  # (type, size, old_pos)
            gyro_samples, accel_samples = [], []  # kept 24B motion samples
            dropped_motion = [0, 0]
            audio_ts = {}  # old audio-data offset -> chunk ts
            # data_start: right after the container metadata item.
            fin.seek(8)
            _, n = struct.unpack("<II", read_exact(fin, 8, "meta head"))
            data_start = 8 + 8 + n
            scan_end = index_off - 8  # frame index head starts the tail
            pos = data_start
            while pos < scan_end:
                fin.seek(pos)
                t, n = struct.unpack("<II", read_exact(fin, 8, "item head"))
                if pos + 8 + n > scan_end:
                    die("item %d overruns data region" % t)
                if t in (T_GYRO_DATA, T_ACCEL_DATA):
                    ver, cnt = struct.unpack("<II", read_exact(fin, 8, "motion head"))
                    if ver != 1 or 8 + cnt * 24 != n:
                        die("bad motion chunk")
                    raw = read_exact(fin, cnt * 24, "motion samples")
                    kept = gyro_samples if t == T_GYRO_DATA else accel_samples
                    slot = 0 if t == T_GYRO_DATA else 1
                    for i in range(cnt):
                        s = raw[i * 24:(i + 1) * 24]
                        (ts,) = struct.unpack("<q", s[:8])
                        if ts < origin:
                            dropped_motion[slot] += 1  # pre-roll, no video
                        else:
                            kept.append(s)
                elif t == T_AUDIO_DATA:
                    # Chunk ts lives in the trailing type-6 item, if present.
                    nxt = pos + 8 + n
                    ts = None
                    if nxt + 16 <= scan_end:
                        fin.seek(nxt)
                        nt, nn = struct.unpack("<II", read_exact(fin, 8, "peek"))
                        if nt == T_AUDIO_META and nn == 8:
                            (ts,) = struct.unpack("<q", read_exact(fin, 8, "audio ts"))
                    audio_ts[pos] = ts
                    items.append((t, n, pos))
                elif t in (T_AUDIO_INDEX, T_GYRO_INDEX, T_ACCEL_INDEX):
                    pass  # stale tail indexes are rebuilt, not copied
                elif t in (T_FRAME, T_META, T_AUDIO_META, 7, 11):
                    items.append((t, n, pos))
                elif t == 10:
                    print("warning: dropping OIS index (offsets go stale)", file=sys.stderr)
                else:
                    die("unknown item type %d at %d" % (t, pos))
                pos += 8 + n
            if pos != scan_end:
                die("data region does not abut frame index")
            # Pass 2: stream-copy, remapping offsets and rebasing timestamps.
            new_off = {}  # old item pos -> new item pos
            new_audio, new_frames = [], []
            dropped_audio = 0
            consumed_meta = set()  # type-6 positions handled inline below
            for (t, n, old) in items:
                if t == T_AUDIO_DATA:
                    ts = audio_ts[old]
                    if ts is None:
                        die("audio chunk at %d has no timestamp" % old)
                    # The ts item always immediately follows its chunk.
                    meta_pos = old + 8 + n
                    fin.seek(meta_pos)
                    mt, mn = struct.unpack("<II", read_exact(fin, 8, "audio meta head"))
                    if mt != T_AUDIO_META or mn != 8:
                        die("audio/meta pairing broken at %d" % old)
                    consumed_meta.add(meta_pos)
                    rel = ts - origin
                    if rel < 0:
                        dropped_audio += 1  # pre-roll without video
                        continue
                    new_off[old] = fout.tell()
                    if stereo:
                        if n % 2:
                            die("odd mono payload at %d" % old)
                        fin.seek(old + 8)  # skip item head, read payload only
                        mono = read_exact(fin, n, "mono audio")
                        dup = bytearray(2 * n)
                        for i in range(0, n, 2):
                            sample = mono[i:i + 2]
                            dup[2 * i:2 * i + 2] = sample      # L
                            dup[2 * i + 2:2 * i + 4] = sample  # R
                        fout.write(struct.pack("<II", T_AUDIO_DATA, 2 * n))
                        fout.write(dup)
                    else:
                        fin.seek(old)
                        fout.write(read_exact(fin, 8, "item head"))
                        copy_bytes(fin, fout, n)
                    new_audio.append((new_off[old], rel))
                    new_off[meta_pos] = fout.tell()
                    fout.write(struct.pack("<II", T_AUDIO_META, 8))
                    fout.write(struct.pack("<q", rel))
                    continue
                if t == T_AUDIO_META:
                    if old not in consumed_meta:
                        die("orphan audio timestamp at %d" % old)
                    continue  # handled inline with its chunk above
                if t == T_META:
                    # Frame metadata: rebase timestamp strings.
                    fin.seek(old + 8)
                    payload = rebase_frame_meta(read_exact(fin, n, "frame meta"), origin)
                    new_off[old] = fout.tell()
                    fout.write(struct.pack("<II", T_META, len(payload)))
                    fout.write(payload)
                    continue
                new_off[old] = fout.tell()
                fin.seek(old)
                fout.write(read_exact(fin, 8, "item head"))
                copy_bytes(fin, fout, n)
                if t == T_FRAME:
                    if old not in frame_ts:
                        die("frame at %d missing from index" % old)
                    new_frames.append((old, frame_ts[old] - origin))
            if len(new_frames) != count:
                die("frame walk found %d, index says %d" % (len(new_frames), count))
            # Audio index FIRST: pre-gyro readers (MotionCam Tools v1.0)
            # stop their tail scan at the first motion item, so trailing
            # motion data must follow the audio index or they find no
            # audio. Order-free readers don't care.
            if new_audio:
                fout.write(struct.pack("<II", T_AUDIO_INDEX, 16 + len(new_audio) * 16))
                fout.write(struct.pack("<qq", len(new_audio), new_audio[0][1]))
                for o, ts in new_audio:
                    fout.write(struct.pack("<qq", o, ts))
            # Coalesced motion: one chunk per sensor, kept samples in order.
            new_gyro, new_accel = [], []
            for kind, samples, out in ((T_GYRO_DATA, gyro_samples, new_gyro),
                                      (T_ACCEL_DATA, accel_samples, new_accel)):
                if not samples:
                    continue
                prev = None
                payload = bytearray()
                for s in samples:
                    (ts,) = struct.unpack("<q", s[:8])
                    if prev is not None and ts < prev:
                        die("motion timestamps regress within coalesced chunk")
                    prev = ts
                    payload += struct.pack("<q", ts - origin) + s[8:]
                (first_ts,) = struct.unpack("<q", payload[:8])
                out.append((fout.tell(), first_ts))
                fout.write(struct.pack("<III", kind, 8 + len(payload), 1))
                fout.write(struct.pack("<I", len(samples)))
                fout.write(payload)
            for kind, entries in ((T_GYRO_INDEX, new_gyro), (T_ACCEL_INDEX, new_accel)):
                if not entries:
                    continue
                fout.write(struct.pack("<III", kind, 8 + len(entries) * 16, 1))
                fout.write(struct.pack("<I", len(entries)))
                for o, ts in entries:
                    fout.write(struct.pack("<qq", o, ts))
            index_pos = fout.tell() + 8
            fout.write(struct.pack("<II", T_INDEX_DATA, len(order) * 16))
            remap = dict(new_frames)
            for old in order:
                fout.write(struct.pack("<qq", new_off[old], remap[old]))
            fout.write(struct.pack("<IIIIq", T_INDEX, 16, FOOTER_MAGIC, len(order), index_pos))
    print("repaired%s %s -> %s (%d frames, %d audio chunks (%d preroll dropped), "
          "gyro 1/%d (%d dropped) accel 1/%d (%d dropped), origin=%d)" % (
              " (stereo)" if stereo else "", src, dst, len(order),
              len(new_audio), dropped_audio, len(gyro_samples),
              dropped_motion[0], len(accel_samples), dropped_motion[1],
              origin))


if __name__ == "__main__":
    main()
