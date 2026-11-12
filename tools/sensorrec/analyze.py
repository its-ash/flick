"""Analyzes a SensorRec CSV: motion around each Motorola ChopChop event, and bursts elsewhere.

Usage: python3 -I analyze.py rec.csv [trace_event_index ...]
"""
import math
import sys


def load(path):
    data = {"L": [], "A": [], "G": [], "CHOP": [], "TORCH": []}
    with open(path) as f:
        for line in f:
            p = line.strip().split(",")
            if not p or p[0] not in data:
                continue
            if p[0] in ("L", "A", "G"):
                data[p[0]].append((float(p[1]), float(p[2]), float(p[3]), float(p[4])))
            elif p[0] == "CHOP":
                data["CHOP"].append(float(p[1]))
            else:
                data["TORCH"].append((float(p[1]), p[2]))
    return data


def mag(s):
    return math.sqrt(s[1] ** 2 + s[2] ** 2 + s[3] ** 2)


def window(series, a, b):
    return [s for s in series if a <= s[0] <= b]


def spikes(series, thr, sep):
    """Local maxima of |v| >= thr, at least sep ms apart (keeps the larger)."""
    out = []
    for i in range(1, len(series) - 1):
        m = mag(series[i])
        if m >= thr and m >= mag(series[i - 1]) and m >= mag(series[i + 1]):
            if out and series[i][0] - out[-1][0] < sep:
                if m > mag(out[-1]):
                    out[-1] = series[i]
            else:
                out.append(series[i])
    return out


def axis(s):
    names = "xyz"
    i = max(range(3), key=lambda k: abs(s[k + 1]))
    return ("+" if s[i + 1] > 0 else "-") + names[i]


def dyn_accel(A):
    """Raw accelerometer minus a 0.3 s low-pass (gravity), 500 Hz."""
    g = list(A[0][1:])
    out = []
    last = A[0][0]
    for t, x, y, z in A:
        dt = max(0.5, t - last)
        last = t
        k = dt / (300 + dt)
        g = [g[0] + k * (x - g[0]), g[1] + k * (y - g[1]), g[2] + k * (z - g[2])]
        out.append((t, x - g[0], y - g[1], z - g[2]))
    return out


def width_above(series, t_peak, level):
    """ms the magnitude stays >= level around t_peak."""
    idx = min(range(len(series)), key=lambda i: abs(series[i][0] - t_peak))
    i = j = idx
    while i > 0 and mag(series[i - 1]) >= level:
        i -= 1
    while j < len(series) - 1 and mag(series[j + 1]) >= level:
        j += 1
    return series[j][0] - series[i][0]


def main():
    d = load(sys.argv[1])
    L, G, CH = d["L"], d["G"], d["CHOP"]
    D = dyn_accel(d["A"])
    print(f"L {len(L)}  A {len(D)}  G {len(G)}  chops {len(CH)}")
    print("\nPer ChopChop event (times relative to the event, ms). Spikes = |linear| peaks >= 6, 120 ms apart")
    print(" #   event_t  spikes(t:mag axis width>=6ms)                                   gyro_max  rawdyn_max  calm_before")
    rows = []
    for n, tc in enumerate(CH):
        w = window(L, tc - 1500, tc + 150)
        sp = spikes(w, 6.0, 120)
        g = window(G, tc - 1500, tc + 150)
        gmax = max((mag(s) for s in g), default=0)
        dmax = max((mag(s) for s in window(D, tc - 1500, tc + 150)), default=0)
        # quiet time before the first spike (|L| < 3)
        first = sp[0][0] if sp else tc
        before = window(L, first - 3000, first - 1)
        quiet = 0
        for s in reversed(before):
            if mag(s) >= 3:
                break
            quiet = first - s[0]
        desc = "  ".join(f"{s[0]-tc:+.0f}:{mag(s):.1f}{axis(s)}w{width_above(w, s[0], 6):.0f}" for s in sp)
        print(f"{n:2d} {tc:9.0f}  {desc:70s} {gmax:6.1f}   {dmax:6.1f}   {quiet:5.0f}")
        rows.append((tc, sp))

    # summary of the two main spikes per event
    print("\nTwo strongest spikes per event (in time order): peak1, peak2, gap, delay of event after spike2")
    p1s, p2s, gaps, lat = [], [], [], []
    for tc, sp in rows:
        if len(sp) < 2:
            print("  only", len(sp), "spike(s) at", tc)
            continue
        top = sorted(sorted(sp, key=mag)[-2:], key=lambda s: s[0])
        a, b = top
        p1s.append(mag(a)); p2s.append(mag(b)); gaps.append(b[0] - a[0]); lat.append(tc - b[0])
        print(f"  {tc:9.0f}  p1 {mag(a):5.1f} {axis(a)}  p2 {mag(b):5.1f} {axis(b)}  gap {b[0]-a[0]:4.0f}  event+{tc-b[0]:4.0f}")

    def st(name, v):
        v = sorted(v)
        if v:
            print(f"  {name:8s} min {v[0]:6.1f}  median {v[len(v)//2]:6.1f}  max {v[-1]:6.1f}")
    print("\nStats")
    st("peak1", p1s); st("peak2", p2s); st("weaker", [min(a, b) for a, b in zip(p1s, p2s)]); st("gap", gaps); st("latency", lat)

    # bursts away from any chop event
    print("\nBursts (|linear| >= 4) not within -1.5 s..+0.3 s of an event: start, length, peak, spikes>=6")
    busy = [(tc - 1500, tc + 300) for tc in CH]
    burst = []
    def flush():
        if burst:
            t0, t1 = burst[0][0], burst[-1][0]
            pk = max(mag(s) for s in burst)
            if not any(a <= t0 <= b or a <= t1 <= b for a, b in busy):
                n6 = len(spikes(window(L, t0 - 5, t1 + 5), 6.0, 120))
                print(f"  {t0/1000:7.2f} s  {t1-t0:5.0f} ms  peak {pk:5.1f}  spikes {n6}")
    lastt = -1e9
    for s in L:
        if mag(s) >= 4:
            if burst and s[0] - lastt > 300:
                flush(); burst.clear()
            burst.append(s); lastt = s[0]
    flush()

    for k in sys.argv[2:]:
        tc = float(k[1:]) if k.startswith("@") else CH[int(k)]
        print(f"\nTrace event {k} at {tc:.0f}, every 10 ms:  t | linear x y z |L| | raw accel x y z |A| | gyro x y z |G|")
        A = d["A"]
        gw = window(G, tc - 900, tc + 200)
        aw = window(A, tc - 900, tc + 200)
        for s in window(L, tc - 800, tc + 150)[::2]:
            a = min(aw, key=lambda q: abs(q[0] - s[0]))
            g = min(gw, key=lambda q: abs(q[0] - s[0]))
            print(f"  {s[0]-tc:+5.0f} | {s[1]:6.1f} {s[2]:6.1f} {s[3]:6.1f} {mag(s):5.1f} | "
                  f"{a[1]:6.1f} {a[2]:6.1f} {a[3]:6.1f} {mag(a):5.1f} | {g[1]:5.1f} {g[2]:5.1f} {g[3]:5.1f} {mag(g):5.1f}")


if __name__ == "__main__":
    main()
