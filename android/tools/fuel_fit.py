#!/usr/bin/env python3
"""fuel_fit.py - tune the dash's fuel model from recorded rides and fill-ups.

The app's model:  litres = k * sum( rpm/1000 * (IDLE_LOAD + throttle/255) * dt )
(no fuel while on overrun: throttle < CUT_THR, rpm > CUT_RPM, moving).

Every FULL fill-up closes a "tank": the litres really used are the partials added
since the previous full fill plus this full fill. Splitting the model into an
rpm-only part A and a throttle part B gives, per tank,

    actual ~= (k * IDLE_LOAD) * A + k * B

so with two or more tanks both k and IDLE_LOAD are fitted (least squares);
with one tank only k is fitted, keeping the current IDLE_LOAD.

Usage:
    adb pull /sdcard/Android/data/com.husky701.dash/files/rides  rides
    python fuel_fit.py rides
"""
import csv
import glob
import os
import sys

CUT_RPM = 2200
CUT_THR = 3
CUT_KMH = 5.0


def read_rows(folder):
    """All S and F rows from every ride file, oldest first, plus the config from the newest header."""
    files = sorted(glob.glob(os.path.join(folder, "ride_*.csv")))
    fills = os.path.join(folder, "fills.csv")
    cfg = {}
    events = []          # (sort_key, kind, data)
    for path in files + ([fills] if os.path.exists(fills) else []):
        header = None
        with open(path, newline="") as fh:
            for line in fh:
                line = line.rstrip("\n")
                if not line:
                    continue
                if line.startswith("#"):
                    if line.startswith("# fuel "):
                        for kv in line[len("# fuel "):].split():
                            k, _, v = kv.partition("=")
                            cfg[k] = v
                    continue
                if line.startswith("type,"):
                    header = line.split(",")
                    continue
                parts = next(csv.reader([line]))
                if parts[0] == "S" and header:
                    row = dict(zip(header, parts))
                    events.append((row["time"], "S", row))
                elif parts[0] == "F":
                    # F,ms,time,full|partial,litres,est_before,used_since_fill,km_since_fill,k
                    events.append((parts[2], "F", {
                        "full": parts[3] == "full", "litres": float(parts[4]),
                        "est_before": float(parts[5]), "used": float(parts[6]),
                        "km": float(parts[7]), "k": float(parts[8]), "time": parts[2]}))
    events.sort(key=lambda e: e[0])
    return events, cfg


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def tanks(events):
    """Split the ride into full-to-full tanks. Returns a list of dicts with A, B, km, actual."""
    out = []
    started = False
    A = B = km = 0.0
    partials = 0.0
    prev = None
    for _, kind, d in events:
        if kind == "F":
            if d["full"]:
                if started and d["litres"] > 0:
                    out.append({"A": A, "B": B, "km": km, "actual": partials + d["litres"],
                                "dash_est": d["used"], "end": d["time"]})
                started = True
                A = B = km = partials = 0.0
            else:
                partials += d["litres"]
            prev = None
            continue
        rpm, thr = num(d.get("rpm")), num(d.get("throttle"))
        spd = num(d.get("speed_kmh")) or 0.0
        ms = num(d.get("ms"))
        if prev is not None and ms is not None and prev[0] is not None and 0 < ms - prev[0] <= 2000:
            dt = (ms - prev[0]) / 1000.0
            if started and rpm is not None and thr is not None and rpm > 300:
                overrun = thr < CUT_THR and rpm > CUT_RPM and spd > CUT_KMH
                if not overrun:
                    A += rpm / 1000.0 * dt
                    B += rpm / 1000.0 * thr / 255.0 * dt
            if started and spd > 1.0:
                km += spd * dt / 3600.0
        prev = (ms,)
    return out


def fit(ts, idle_now):
    if not ts:
        return None
    if len(ts) == 1:
        t = ts[0]
        k = t["actual"] / (idle_now * t["A"] + t["B"])
        return k, idle_now
    # least squares for actual = a*A + b*B
    saa = sum(t["A"] ** 2 for t in ts)
    sbb = sum(t["B"] ** 2 for t in ts)
    sab = sum(t["A"] * t["B"] for t in ts)
    sya = sum(t["actual"] * t["A"] for t in ts)
    syb = sum(t["actual"] * t["B"] for t in ts)
    det = saa * sbb - sab * sab
    if abs(det) < 1e-12:
        return fit(ts[-1:], idle_now)
    a = (sya * sbb - syb * sab) / det
    b = (syb * saa - sya * sab) / det
    if b <= 0:
        return fit(ts[-1:], idle_now)
    idle = max(0.02, min(0.40, a / b))
    return b, idle


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else "rides"
    events, cfg = read_rows(folder)
    k_now = float(cfg.get("k", 0.00066))
    idle_now = float(cfg.get("idle_load", 0.10))
    n_samples = sum(1 for e in events if e[1] == "S")
    n_fills = sum(1 for e in events if e[1] == "F")
    print(f"{n_samples} samples, {n_fills} fill-ups in {folder}")
    print(f"current model: k={k_now:.6f}  idle_load={idle_now:.3f}")
    ts = tanks(events)
    if not ts:
        print("No full-to-full tank yet: fill to FULL, ride, fill to FULL again (entering the litres).")
        return
    print("\ntank  ended                 km     actual L   dash est L   L/100")
    for i, t in enumerate(ts, 1):
        l100 = t["actual"] / t["km"] * 100 if t["km"] > 1 else float("nan")
        print(f"{i:>4}  {t['end'][:19]:<20} {t['km']:>6.1f}   {t['actual']:>8.2f}   {t['dash_est']:>10.2f}   {l100:>5.2f}")
    res = fit(ts, idle_now)
    k, idle = res
    print(f"\nfitted model:  k={k:.6f}  idle_load={idle:.3f}"
          + ("   (k only: one tank so far)" if len(ts) == 1 else f"   ({len(ts)} tanks)"))
    for t in ts:
        est = k * (idle * t["A"] + t["B"])
        t["err"] = (est - t["actual"]) / t["actual"] * 100
    print("per-tank error with fitted model: " + ", ".join(f"{t['err']:+.1f}%" for t in ts))
    print("\nSuggested DashConfig.kt / config.h values:")
    print(f"  FUEL_K_DEFAULT = {k:.6f}f")
    print(f"  FUEL_IDLE_LOAD = {idle:.3f}f")


if __name__ == "__main__":
    main()
