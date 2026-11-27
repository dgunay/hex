#!/usr/bin/env python3
"""mkgif.py OUT.gif TAKE [TAKE] [--labels A B] [--start S] [--end E] [--top PX] [--bottom PX] [--hold S] [--width W] [--nodots]
TAKE is raw/NAME (raw/NAME.mp4, .rec0, .taps). Touch dots from the tap log; two takes go side by side."""
import argparse, re, subprocess, sys
from pathlib import Path
D = Path(__file__).resolve().parent
F = '/usr/share/fonts/noto/NotoSans-Medium.ttf'
p = argparse.ArgumentParser(); p.add_argument('out'); p.add_argument('takes', nargs='+')
p.add_argument('--labels', nargs='*'); p.add_argument('--start', type=float, default=0); p.add_argument('--end', type=float, default=999)
p.add_argument('--top', type=int, default=1330); p.add_argument('--bottom', type=int, default=2400); p.add_argument('--hold', type=float, default=2)
p.add_argument('--width', type=int); p.add_argument('--nodots', action='store_true'); p.add_argument('--fps', type=int, default=20)
a = p.parse_args()
pair = len(a.takes) == 2
W = a.width or (380 if pair else 480)

def scene_times(mp4, x, y, w, h):
    r = subprocess.run(['ffmpeg', '-v', 'info', '-i', mp4, '-vf', f"crop={w}:{h}:{x}:{y},select='gt(scene,0.01)',showinfo", '-f', 'null', '-'],
                       capture_output=True, text=True).stderr
    return [float(t) for t in re.findall(r'pts_time:([0-9.]+)', r)]

def taps(take):
    tp = Path(take + '.taps'); r0 = Path(take + '.rec0')
    if not tp.exists() or not r0.exists(): return []
    rec0 = float(r0.read_text().split()[0])
    out = []
    for line in tp.read_text().split('\n'):
        f = line.split()
        if len(f) < 4: continue
        t, x, y, ms = float(f[0]) - rec0, int(f[1]), int(f[2]), int(f[3])
        x2, y2 = (int(f[4]), int(f[5])) if len(f) >= 6 else (x, y)
        out.append((t, x, y, ms / 1000, x2, y2))
    if not out: return out
    # Line the log up with the video: the first press's preview (a scene change in the keys) shows the true time.
    first = out[0]
    # Only the first key and the space above it, where its preview pops up.
    _, fx, fy, _, _, _ = first
    seen = [s for s in scene_times(take + '.mp4', max(0, fx - 100), max(0, fy - 300), 200, 360) if s > first[0] - 0.4]
    delta = min(max((seen[0] - first[0]) if seen else 0.15, -0.3), 0.6)
    print(take, 'tap log offset', round(delta, 3), file=sys.stderr)
    return [(t + delta, x, y, d, x2, y2) for t, x, y, d, x2, y2 in out]

inputs = []; chains = []
for i, take in enumerate(a.takes):
    inputs += ['-i', take + '.mp4']
for i, take in enumerate(a.takes):
    inputs += ['-i', str(D / 'dot.png')]
n = len(a.takes)
for i, take in enumerate(a.takes):
    cur = f'[{i}:v]'
    if not a.nodots:
        ts = taps(take)
        k = len(ts)
        if k:
            chains.append(f'[{n + i}:v]split={k}' + ''.join(f'[d{i}_{j}]' for j in range(k)))
        for j, (t, x, y, d, x2, y2) in enumerate(ts):
            show = max(d, 0.09) + 0.12
            if (x2, y2) != (x, y):
                xe = f"{x}+({x2}-{x})*min(max((t-{t:.3f})/{d:.3f},0),1)-36"; ye = f"{y}+({y2}-{y})*min(max((t-{t:.3f})/{d:.3f},0),1)-36"
            else:
                xe, ye = str(x - 36), str(y - 36)
            nxt = f'[o{i}_{j}]'
            chains.append(f"{cur}[d{i}_{j}]overlay=x='{xe}':y='{ye}':enable='between(t,{t:.3f},{t + show:.3f})'{nxt}")
            cur = nxt
    h = a.bottom - a.top
    lab = ''
    if pair:
        L = (a.labels[i] if a.labels else '').replace('\\', '\\\\').replace(':', '\\:').replace("'", '’')
        lab = f",pad=iw+12:ih+46:6:46:color=0x101114,drawtext=fontfile={F}:text='{L}':fontcolor=0xD8DADF:fontsize=19:x=(w-text_w)/2:y=14"
    chains.append(f"{cur}trim=start={a.start}:end={a.end},setpts=PTS-STARTPTS,fps={a.fps},crop=1080:{h}:0:{a.top},scale={W}:-2:flags=lanczos{lab}[v{i}]")
if pair:
    chains.append('[v0][v1]hstack=2[vv]')
else:
    chains.append('[v0]null[vv]')
chains.append(f'[vv]tpad=stop_mode=clone:stop_duration={a.hold},split[x][y];[x]palettegen=max_colors=96:stats_mode=diff[p];[y][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle')
subprocess.run(['ffmpeg', '-v', 'error', '-y', *inputs, '-filter_complex', ';'.join(chains), a.out], check=True)
print(a.out, Path(a.out).stat().st_size // 1024, 'KB')
