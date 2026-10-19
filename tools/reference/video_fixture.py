"""Writes the synthetic video assets for app instrumented tests (add-video-processing design D11).

usage: python video_fixture.py [outdir]   (default: app/src/androidTest/assets)

Everything is generated from formulas with ffmpeg (the imageio-ffmpeg binary when installed); there is
no personal media in the output.
  synthetic_drift.mp4  2 s, 640x360, 30 fps, H.264 + AAC (440 Hz tone), underwater-like texture whose
                       colour drifts from green-blue to deep blue, a bright bar moving across (an edge),
                       creation time 2026-07-14T08:32:05Z, a location in both MP4 forms (3GPP `loci` and
                       QuickTime `\xa9xyz`), display rotation 90, and an extra text track standing in for camera
                       telemetry (exports must drop all of them).
  synthetic_hlg.mp4    1 s, 640x360, 30 fps, 10-bit HEVC tagged BT.2020 / HLG, no audio.
  synthetic_long.mp4   20 s, 320x180, 30 fps, H.264 + AAC at low bitrates, the same texture drifting more slowly, for
                       viewer tests that need playback to last (hold, scrub, slider while playing).
"""
import os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
CREATION_TIME = "2026-07-14T08:32:05.000000Z"
LOCATION = "+48.8584+002.2945/"

# 8-bit values; T is the time in seconds, X/Y the pixel position
R = "clip(25+15*sin(X/7+Y/11)+if(lt(abs(X-80-240*T)\\,18)\\,180\\,0)\\,0\\,255)"
G = "clip(150-35*T+30*sin(X/13)*cos(Y/9)+if(lt(abs(X-80-240*T)\\,18)\\,80\\,0)\\,0\\,255)"
B = "clip(140+30*T+30*cos(X/17+Y/5)+if(lt(abs(X-80-240*T)\\,18)\\,60\\,0)\\,0\\,255)"


def ffmpeg():
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError:
        return "ffmpeg"


def run(*args):
    subprocess.run([ffmpeg(), "-loglevel", "error", "-y", *args], check=True)


def video_source(seconds, size="640x360", speed=1.0):
    def slow(expr):
        return expr.replace("T", f"(T*{speed})")
    return f"color=c=black:s={size}:r=30:d={seconds},format=gbrp,geq=r='{slow(R)}':g='{slow(G)}':b='{slow(B)}'"


def drift(out, tmp):
    srt = os.path.join(tmp, "telemetry.srt")
    open(srt, "w").write("1\n00:00:00,000 --> 00:00:02,000\nGPS 48.8584 2.2945\n")
    plain = os.path.join(tmp, "plain.mp4")
    run("-f", "lavfi", "-i", video_source(2), "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
        "-i", srt, "-map", "0:v", "-map", "1:a", "-map", "2:s",
        "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-g", "30", "-b:v", "1500k",
        "-c:a", "aac", "-b:a", "96k", "-c:s", "mov_text",
        "-metadata", f"creation_time={CREATION_TIME}", "-metadata", f"location={LOCATION}", plain)
    # The rotation goes into the track's display matrix; set on the input so stream copy keeps it. ffmpeg counts
    # counter-clockwise, Android (MediaFormat.KEY_ROTATION) clockwise: -90 here is Android's 90.
    run("-display_rotation:v:0", "-90", "-i", plain, "-map", "0", "-c", "copy",
        "-metadata", f"creation_time={CREATION_TIME}", "-metadata", f"location={LOCATION}", out)
    add_xyz(out)


def add_xyz(path):
    """Adds a QuickTime-style location box (`\xa9xyz`, as Android's MediaMuxer and phone cameras write) to the moov/udta of
    the file, next to the 3GPP `loci` box ffmpeg writes. moov is last in the file, so no chunk offsets move."""
    data = bytearray(open(path, "rb").read())
    moov = data.rindex(b"moov") - 4
    assert moov + int.from_bytes(data[moov:moov + 4], "big") == len(data), "moov must be the last box"
    udta = data.index(b"udta", moov) - 4
    text = LOCATION.encode()
    box = (8 + 4 + len(text)).to_bytes(4, "big") + b"\xa9xyz" + len(text).to_bytes(2, "big") + (0x15C7).to_bytes(2, "big") + text
    end = udta + int.from_bytes(data[udta:udta + 4], "big")
    data[end:end] = box
    for start in (udta, moov):
        size = int.from_bytes(data[start:start + 4], "big") + len(box)
        data[start:start + 4] = size.to_bytes(4, "big")
    open(path, "wb").write(data)


def hlg(out):
    run("-f", "lavfi", "-i", video_source(1), "-c:v", "libx265", "-pix_fmt", "yuv420p10le", "-tag:v", "hvc1",
        "-x265-params", "log-level=error:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc",
        "-color_primaries", "bt2020", "-color_trc", "arib-std-b67", "-colorspace", "bt2020nc", "-b:v", "1500k",
        "-metadata", f"creation_time={CREATION_TIME}", out)


def long_clip(out):
    run("-f", "lavfi", "-i", video_source(20, "320x180", 0.1), "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=20",
        "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-g", "30", "-b:v", "250k",
        "-c:a", "aac", "-b:a", "32k", "-metadata", f"creation_time={CREATION_TIME}", out)


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "..", "app", "src", "androidTest", "assets")
    os.makedirs(outdir, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        drift(os.path.join(outdir, "synthetic_drift.mp4"), tmp)
    hlg(os.path.join(outdir, "synthetic_hlg.mp4"))
    long_clip(os.path.join(outdir, "synthetic_long.mp4"))
    for name in ("synthetic_drift.mp4", "synthetic_hlg.mp4", "synthetic_long.mp4"):
        path = os.path.join(outdir, name)
        print(f"wrote {os.path.relpath(path)} ({os.path.getsize(path)} bytes)")


if __name__ == "__main__":
    main()
