#!/usr/bin/env python3
"""Resolve the annotation-processor closure for the manual build.

The Room compiler (androidx.room:room-compiler) is a kapt processor: it is
needed on the kapt apclasspath, but must NEVER be dexed into the APK.
This script reuses resolve_deps.py's POM-walking resolver and downloads the
processor closure into processor/ (separate from deps/, which is dexed).

Usage: python3 resolve_processors.py
Outputs:
  processor/        downloaded .jar files (processor + its transitive deps)
  processor.json    manifest
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import resolve_deps as rd

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "processor")

PROCESSOR_ROOTS = [
    ("androidx.room", "room-compiler", "2.6.1"),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(rd.POM_CACHE, exist_ok=True)

    rd.load_bom("androidx.compose", "compose-bom", "2024.09.00")
    for k, v in {
        ("androidx.lifecycle", "lifecycle-viewmodel-ktx"): "2.8.5",
        ("androidx.lifecycle", "lifecycle-viewmodel-savedstate"): "2.8.5",
        ("androidx.lifecycle", "lifecycle-livedata-core"): "2.8.5",
        ("androidx.lifecycle", "lifecycle-livedata"): "2.8.5",
        ("androidx.collection", "collection-ktx"): "1.4.0",
    }.items():
        rd.BOM_VERSIONS.setdefault(k, v)

    resolved = {}
    queue = [(g, a, v, 0) for (g, a, v) in PROCESSOR_ROOTS]
    packaging = {}

    while queue:
        g, a, v, depth = queue.pop(0)
        key = (g, a)
        if key in resolved:
            continue
        pom_file, v = rd.fetch_pom(g, a, v)
        resolved[key] = v
        root = rd.parse_pom(pom_file)
        props, mgmt, bom_imports, top = rd.collect_props_and_mgmt(root)
        for bg, ba, bv in bom_imports:
            bom_pom, _ = rd.fetch_pom(bg, ba, bv)
            bpom = rd.parse_pom(bom_pom)
            bprops, bmgmt, _, _ = rd.collect_props_and_mgmt(bpom)
            for k, val in bmgmt.items():
                mgmt.setdefault(k, val)
        pkg = rd.txt(top, "packaging") or "jar"
        packaging[key] = pkg
        for dg, da, dv in rd.pom_dependencies(top, props, mgmt):
            if (dg, da) not in resolved:
                queue.append((dg, da, dv, depth + 1))

    # Same KMP deduplication as the runtime resolver.
    names = {(g, a) for (g, a) in resolved}
    drop = set()
    for (g, a) in list(resolved):
        for suffix in ("-android", "-jvm"):
            if (g, a + suffix) in names:
                drop.add((g, a))
                break
    drop.add(("org.jetbrains.kotlin", "kotlin-stdlib-common"))
    for key in sorted(drop):
        if key in resolved:
            print(f"  drop duplicate {key[0]}:{key[1]}:{resolved[key]}")
            del resolved[key]

    print(f"Resolved {len(resolved)} processor artifacts")
    manifest = []
    for (g, a), v in sorted(resolved.items()):
        pkg = packaging.get((g, a), "jar")
        ext = "aar" if pkg == "aar" else "jar"
        fname = f"{a}-{v}.{ext}"
        fpath = os.path.join(OUT, fname)
        url = f"{rd.base_for(g)}/{rd.group_path(g)}/{a}/{v}/{fname}"
        ok = rd.curl(url, fpath)
        print(f"  {'OK ' if ok else 'FAIL'} {g}:{a}:{v} ({ext})")
        if not ok:
            raise RuntimeError(f"download failed: {g}:{a}:{v}")
        manifest.append({
            "group": g, "artifact": a, "version": v,
            "packaging": pkg, "file": fpath,
        })

    with open(os.path.join(ROOT, "processor.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"Wrote {os.path.join(ROOT, 'processor.json')}")


if __name__ == "__main__":
    main()
