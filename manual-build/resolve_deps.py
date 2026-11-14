#!/usr/bin/env python3
"""Resolve + download the Artlog round-1 dependency closure from Maven Central.

Runs with curl (the sandbox HTTP proxy works for non-JVM tools). JVM processes
cannot do any TCP here, which is why Gradle itself cannot run in this VM.
Outputs:
  deps/           downloaded .jar / .aar files
  deps.json       manifest: every artifact, packaging, file, AAR package name
"""
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

CENTRAL = "https://repo.maven.apache.org/maven2"
GOOGLE = "https://dl.google.com/dl/android/maven2"


def base_for(group):
    # AndroidX / Google artifacts live on Google Maven; everything else on Central.
    if group.startswith("androidx.") or group.startswith("com.google.android"):
        return GOOGLE
    return CENTRAL
ROOT = os.path.dirname(os.path.abspath(__file__))
DEPS = os.path.join(ROOT, "deps")
POM_CACHE = os.path.join(ROOT, "pom-cache")
NS = "{http://maven.apache.org/POM/4.0.0}"

# Root dependencies (versions pinned by Compose BOM 2024.09.00 where applicable).
ROOTS = [
    ("androidx.core", "core-ktx", "1.13.1"),
    ("androidx.lifecycle", "lifecycle-runtime-compose", "2.8.5"),
    ("androidx.lifecycle", "lifecycle-viewmodel", "2.8.5"),
    ("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.8.5"),
    ("androidx.activity", "activity-compose", "1.9.2"),
    ("androidx.compose.ui", "ui", "1.7.2"),
    ("androidx.compose.ui", "ui-graphics", "1.7.2"),
    ("androidx.compose.material3", "material3", "1.3.0"),
    ("androidx.compose.material", "material-icons-extended", "1.7.2"),
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.20"),
    ("org.jetbrains.kotlin", "kotlin-stdlib-jdk8", "2.0.20"),
    # Round 2: Room (database) + Coil (async image loading) + coroutines.
    ("androidx.room", "room-runtime", "2.6.1"),
    ("androidx.room", "room-ktx", "2.6.1"),
    ("io.coil-kt", "coil-compose", "2.7.0"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.7.3"),
    # Next iteration (stages 5-6): Media3 playback. Kept AFTER the
    # existing roots so the first-seen-wins resolution keeps the
    # project's newer core/lifecycle/collection versions.
    ("androidx.media3", "media3-exoplayer", "1.4.1"),
    ("androidx.media3", "media3-session", "1.4.1"),
]

SKIP_SCOPES = {"test", "provided", "system"}
# Artifacts whose POMs are known to be irrelevant / problematic for the build.
SKIP_ARTIFACTS = {
    ("androidx.annotation", "annotation-experimental"),
}


def curl(url, out_path):
    if os.path.exists(out_path) and os.path.getsize(out_path) > 0:
        return True
    r = subprocess.run(
        ["curl", "-sSfL", "--retry", "3", "-o", out_path, url],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        if os.path.exists(out_path):
            os.remove(out_path)
        print(f"  !! curl failed for {url}: {r.stderr.strip()[:200]}", file=sys.stderr)
        return False
    return True


def group_path(g):
    return g.replace(".", "/")


def pom_path(g, a, v):
    return os.path.join(POM_CACHE, group_path(g), a, v, f"{a}-{v}.pom")


def _version_key(v):
    # numeric-ish ordering for release versions like 1.13.1 / 2.8.5 / 1.7.2
    parts = []
    for chunk in re.split(r"[.\-+]", v):
        parts.append((0, int(chunk), "") if chunk.isdigit() else (1, 0, chunk))
    return parts


def resolve_range(g, a, spec):
    """Resolve a Maven version range like [2.8.5] or [1.0,2.0) via metadata.

    NOTE: a single version in brackets, [x], means EXACTLY x in Maven.
    """
    m = re.match(r"^([\[\(])([^,\)\]]*)(?:,([^\)\]]*))?([\]\)])$", spec.strip())
    if not m:
        return spec
    lo_bracket, lo, hi, hi_bracket = m.groups()
    lo = (lo or "").strip() or None
    hi = (hi or "").strip() or None
    if hi is None:
        # "[x]" with no comma: exactly x.
        return lo
    meta_path = os.path.join(POM_CACHE, group_path(g), a, "maven-metadata.xml")
    if not os.path.exists(meta_path):
        os.makedirs(os.path.dirname(meta_path), exist_ok=True)
        url = f"{base_for(g)}/{group_path(g)}/{a}/maven-metadata.xml"
        if not curl(url, meta_path):
            raise RuntimeError(f"cannot fetch metadata {g}:{a}")
    root = ET.parse(meta_path).getroot()
    # maven-metadata.xml has no namespace (unlike POMs)
    versions = [e.text for e in root.findall(".//version") if e.text]
    cands = []
    for v in versions:
        if lo and _version_key(v) < _version_key(lo):
            continue
        if lo and lo_bracket == "(" and _version_key(v) == _version_key(lo):
            continue
        if hi and _version_key(v) > _version_key(hi):
            continue
        if hi and hi_bracket == ")" and _version_key(v) == _version_key(hi):
            continue
        cands.append(v)
    if not cands:
        raise RuntimeError(f"no version in range {spec} for {g}:{a}")
    return max(cands, key=_version_key)


def fetch_pom(g, a, v):
    if v.startswith("[") or v.startswith("("):
        v = resolve_range(g, a, v)
    p = pom_path(g, a, v)
    if not os.path.exists(p):
        os.makedirs(os.path.dirname(p), exist_ok=True)
        url = f"{base_for(g)}/{group_path(g)}/{a}/{v}/{a}-{v}.pom"
        if not curl(url, p):
            raise RuntimeError(f"cannot fetch POM {g}:{a}:{v}")
    return p, v


def parse_pom(path):
    tree = ET.parse(path)
    return tree.getroot()


def txt(elem, tag):
    child = elem.find(f"{NS}{tag}")
    return child.text.strip() if child is not None and child.text else None


def resolve_props(value, props):
    """Resolve ${...} placeholders against a property map (a few passes)."""
    if value is None:
        return None
    for _ in range(5):
        m = re.search(r"\$\{([^}]+)\}", value)
        if not m:
            break
        key = m.group(1)
        if key in props:
            value = value.replace(m.group(0), props[key])
        else:
            break
    return value


def collect_props_and_mgmt(root):
    """Walk the POM + its parents, collecting properties and dependencyManagement."""
    props = {}
    mgmt = {}  # (group, artifact) -> version
    bom_imports = []
    chain = []
    cur = root
    while cur is not None:
        chain.append(cur)
        parent = cur.find(f"{NS}parent")
        if parent is None:
            break
        pg, pa, pv = (txt(parent, t) for t in ("groupId", "artifactId", "version"))
        if not (pg and pa and pv):
            break
        parent_pom, _ = fetch_pom(pg, pa, pv)
        cur = parse_pom(parent_pom)
    # parents first so child values win
    for pom in reversed(chain):
        for p in pom.findall(f"{NS}properties/*"):
            if p.tag.startswith(NS):
                props[p.tag[len(NS):]] = p.text.strip() if p.text else ""
        dm = pom.find(f"{NS}dependencyManagement/{NS}dependencies")
        if dm is not None:
            for d in dm.findall(f"{NS}dependency"):
                g, a = txt(d, "groupId"), txt(d, "artifactId")
                v, scope, typ = txt(d, "version"), txt(d, "scope"), txt(d, "type")
                if g and a and v:
                    mgmt[(g, a)] = resolve_props(v, props)
                if scope == "import" and typ == "pom" and g and a and v:
                    bom_imports.append((g, a, resolve_props(v, props)))
    # project.version / groupId fallbacks
    if "project.version" not in props:
        v = txt(chain[0], "version")
        if v:
            props["project.version"] = v
    return props, mgmt, bom_imports, chain[0]


def pom_dependencies(root, props, mgmt):
    deps = []
    dep_elem = root.find(f"{NS}dependencies")
    if dep_elem is None:
        return deps
    for d in dep_elem.findall(f"{NS}dependency"):
        g = resolve_props(txt(d, "groupId"), props)
        a = resolve_props(txt(d, "artifactId"), props)
        v = resolve_props(txt(d, "version"), props)
        scope = txt(d, "scope") or "compile"
        optional = txt(d, "optional") == "true"
        if v is None and (g, a) in mgmt:
            v = mgmt[(g, a)]
        # The Compose BOM wins over POM-declared versions/ranges,
        # mirroring Gradle's platform() behavior.
        if (g, a) in BOM_VERSIONS:
            v = BOM_VERSIONS[(g, a)]
        if not (g and a and v):
            continue
        if scope in SKIP_SCOPES or optional:
            continue
        if (g, a) in SKIP_ARTIFACTS:
            continue
        deps.append((g, a, v))
    return deps


BOM_VERSIONS = {}


def load_bom(g, a, v):
    """Load a BOM POM and record its dependencyManagement as version authority."""
    bom_pom, _ = fetch_pom(g, a, v)
    root = parse_pom(bom_pom)
    props, mgmt, bom_imports, _ = collect_props_and_mgmt(root)
    for k, val in mgmt.items():
        BOM_VERSIONS.setdefault(k, val)
    for bg, ba, bv in bom_imports:
        load_bom(bg, ba, bv)


def main():
    os.makedirs(DEPS, exist_ok=True)
    os.makedirs(POM_CACHE, exist_ok=True)

    # The Compose BOM is the version authority, exactly as Gradle's
    # platform("androidx.compose:compose-bom:2024.09.00") is.
    load_bom("androidx.compose", "compose-bom", "2024.09.00")
    # No lifecycle BOM is published, so pin the drifting lifecycle modules
    # to the 2.8.5 release train explicitly: older -ktx / -livedata versions
    # duplicate classes that moved into the base artifact in 2.8.x.
    for k, v in {
        ("androidx.lifecycle", "lifecycle-viewmodel-ktx"): "2.8.5",
        ("androidx.lifecycle", "lifecycle-viewmodel-savedstate"): "2.8.5",
        ("androidx.lifecycle", "lifecycle-livedata-core"): "2.8.5",
        ("androidx.lifecycle", "lifecycle-livedata"): "2.8.5",
        # collection-ktx must match collection 1.4.0 (Gradle picks the
        # highest declared version; the older 1.2.0 duplicates Kt helpers
        # that moved into collection-jvm 1.4.0).
        ("androidx.collection", "collection-ktx"): "1.4.0",
        # exifinterface must stay at the pre-Media3 1.3.7: Media3's POM
        # declares 1.3.6 and would otherwise win first-seen and
        # downgrade it (found during the stage-5 Media3 merge).
        ("androidx.exifinterface", "exifinterface"): "1.3.7",
    }.items():
        BOM_VERSIONS.setdefault(k, v)
    print(f"BOM pins {len(BOM_VERSIONS)} artifact versions")

    resolved = {}   # (g, a) -> version (nearest wins: first-seen at shallowest depth)
    queue = [(g, a, v, 0) for (g, a, v) in ROOTS]
    packaging = {}

    while queue:
        g, a, v, depth = queue.pop(0)
        key = (g, a)
        if key in resolved:
            continue
        pom_file, v = fetch_pom(g, a, v)
        resolved[key] = v
        root = parse_pom(pom_file)
        props, mgmt, bom_imports, top = collect_props_and_mgmt(root)
        # BOM imports add their managed versions
        for bg, ba, bv in bom_imports:
            bom_pom, _ = fetch_pom(bg, ba, bv)
            bpom = parse_pom(bom_pom)
            bprops, bmgmt, _, _ = collect_props_and_mgmt(bpom)
            for k, val in bmgmt.items():
                mgmt.setdefault(k, val)
        # packaging of this artifact
        pkg = txt(top, "packaging") or "jar"
        packaging[key] = pkg
        for dg, da, dv in pom_dependencies(top, props, mgmt):
            if (dg, da) not in resolved:
                queue.append((dg, da, dv, depth + 1))

    # KMP deduplication: when a plain artifact and its -android / -jvm
    # siblings were all resolved, keep only the Android one (the classes
    # are the same; keeping both would duplicate classes in the dex).
    # Also drop kotlin-stdlib-common (its classes are inside kotlin-stdlib)
    # and kotlinx-coroutines-core (same classes as core-jvm).
    names = {(g, a) for (g, a) in resolved}
    drop = set()
    for (g, a) in list(resolved):
        for suffix in ("-android", "-jvm"):
            if (g, a + suffix) in names:
                drop.add((g, a))
                break
    drop.add(("org.jetbrains.kotlin", "kotlin-stdlib-common"))
    # Stage 5 (Media3 merge): media3-common pulls in full Guava, which
    # already contains com.google.common.util.concurrent.ListenableFuture.
    # Keeping the standalone listenablefuture-1.0.jar as well makes D8
    # fail with "Type ListenableFuture is defined multiple times"
    # (found by the stage-4 build, 2026-11-13). Drop the standalone
    # jar whenever full Guava is resolved — Gradle resolves the same
    # way (one artifact wins), and Media3 needs Guava's other classes
    # too, so Guava is the one to keep.
    if ("com.google.guava", "guava") in resolved:
        drop.add(("com.google.guava", "listenablefuture"))
    for key in sorted(drop):
        if key in resolved:
            print(f"  drop duplicate {key[0]}:{key[1]}:{resolved[key]}")
            del resolved[key]

    print(f"Resolved {len(resolved)} artifacts")
    manifest = []
    for (g, a), v in sorted(resolved.items()):
        pkg = packaging.get((g, a), "jar")
        ext = "aar" if pkg == "aar" else "jar"
        fname = f"{a}-{v}.{ext}"
        fpath = os.path.join(DEPS, fname)
        url = f"{base_for(g)}/{group_path(g)}/{a}/{v}/{fname}"
        ok = curl(url, fpath)
        print(f"  {'OK ' if ok else 'FAIL'} {g}:{a}:{v} ({ext})")
        if not ok:
            raise RuntimeError(f"download failed: {g}:{a}:{v}")
        manifest.append({
            "group": g, "artifact": a, "version": v,
            "packaging": pkg, "file": fpath,
        })

    with open(os.path.join(ROOT, "deps.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"Wrote {os.path.join(ROOT, 'deps.json')}")


if __name__ == "__main__":
    main()
