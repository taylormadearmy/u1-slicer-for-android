#!/usr/bin/env python3
"""Distil the OpenPrintTag database (MIT) into the app's bundled filament library asset.

Reads a checkout of github.com/OpenPrintTag/openprinttag-database, keeps class: FFF
materials only, and emits a minified JSON asset. Short keys keep the asset small:
  s=slug  b=brand display name  n=name  m=material (canonical)  mr=raw material when
  it differs  h=#RRGGBB  td=transmission distance  ri=refractive index  d=density
  nl/nh=min/max nozzle temp  bl/bh=min/max bed temp.  Absent values are omitted.

Usage:
  python convert.py --db /path/to/openprinttag-database --out ../../app/src/main/assets/filament_library.json
"""
import argparse
import datetime
import json
import os
import subprocess

import yaml

# Map OpenPrintTag `type` values onto the app's canonical material set where a
# clean mapping exists. Everything else passes through unchanged (displayable,
# matched conservatively).
CANONICAL = {
    "PA6": "PA", "PA11": "PA", "PA12": "PA", "PA612": "PA", "PA66": "PA", "PPA": "PA",
}


def canonical_material(raw):
    return CANONICAL.get(raw, raw)


def normalise_hex(color_rgba):
    """'#rrggbbaa' or '#rrggbb' -> '#RRGGBB'; None/garbage -> None."""
    if not color_rgba or not isinstance(color_rgba, str):
        return None
    h = color_rgba.strip().lstrip("#")
    if len(h) == 8:
        h = h[:6]
    if len(h) != 6:
        return None
    try:
        int(h, 16)
    except ValueError:
        return None
    return "#" + h.upper()


def load_brands(db_root):
    brands = {}
    brands_dir = os.path.join(db_root, "data", "brands")
    for fn in os.listdir(brands_dir):
        if not fn.endswith(".yaml"):
            continue
        with open(os.path.join(brands_dir, fn), encoding="utf-8") as f:
            doc = yaml.safe_load(f)
        if doc and doc.get("slug"):
            brands[doc["slug"]] = doc.get("name") or doc["slug"]
    return brands


def convert_material(doc, brands):
    """One parsed material YAML -> entry dict, or None if not FFF / unusable."""
    if not isinstance(doc, dict) or doc.get("class") != "FFF":
        return None
    slug = doc.get("slug")
    name = doc.get("name")
    if not slug or not name:
        return None
    brand_slug = (doc.get("brand") or {}).get("slug", "")
    raw_type = doc.get("type") or ""
    material = canonical_material(raw_type) if raw_type else ""
    entry = {
        "s": slug,
        "b": brands.get(brand_slug, brand_slug),
        "n": name,
        "m": material,
    }
    if raw_type and raw_type != material:
        entry["mr"] = raw_type
    hexcol = normalise_hex((doc.get("primary_color") or {}).get("color_rgba"))
    if hexcol:
        entry["h"] = hexcol
    if isinstance(doc.get("transmission_distance"), (int, float)):
        entry["td"] = doc["transmission_distance"]
    if isinstance(doc.get("refractive_index"), (int, float)):
        entry["ri"] = doc["refractive_index"]
    props = doc.get("properties") or {}
    if isinstance(props.get("density"), (int, float)):
        entry["d"] = props["density"]
    for src, key in (
        ("min_print_temperature", "nl"), ("max_print_temperature", "nh"),
        ("min_bed_temperature", "bl"), ("max_bed_temperature", "bh"),
    ):
        v = props.get(src)
        if isinstance(v, (int, float)):
            entry[key] = int(round(v))
    return entry


def convert(db_root, commit, date):
    brands = load_brands(db_root)
    materials_dir = os.path.join(db_root, "data", "materials")
    entries = []
    for brand_dir in sorted(os.listdir(materials_dir)):
        full = os.path.join(materials_dir, brand_dir)
        if not os.path.isdir(full):
            continue
        for fn in sorted(os.listdir(full)):
            if not fn.endswith(".yaml"):
                continue
            path = os.path.join(full, fn)
            try:
                with open(path, encoding="utf-8") as f:
                    doc = yaml.safe_load(f)
            except yaml.YAMLError as e:
                raise RuntimeError(f"Malformed YAML in {path}: {e}") from e
            entry = convert_material(doc, brands)
            if entry:
                entries.append(entry)
    entries.sort(key=lambda e: (e["b"].lower(), e["n"].lower()))
    return {
        "schema": 1,
        "source": "OpenPrintTag/openprinttag-database",
        "commit": commit,
        "date": date,
        "count": len(entries),
        "entries": entries,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", required=True, help="path to openprinttag-database checkout")
    ap.add_argument("--out", required=True, help="output JSON path")
    ap.add_argument("--commit", default=None, help="database commit SHA (default: git rev-parse in --db)")
    args = ap.parse_args()
    commit = args.commit or subprocess.check_output(
        ["git", "-C", args.db, "rev-parse", "--short", "HEAD"], text=True).strip()
    date = datetime.date.today().isoformat()
    result = convert(args.db, commit=commit, date=date)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, separators=(",", ":"), ensure_ascii=False)
    size_mb = os.path.getsize(args.out) / 1e6
    print(f"Wrote {result['count']} FFF entries ({size_mb:.2f} MB) to {args.out} "
          f"[commit {commit}, {date}]")


if __name__ == "__main__":
    main()
