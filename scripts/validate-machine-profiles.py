#!/usr/bin/env python3
"""Validate the pinned, partial Orca profile snapshots used by JVM tests."""

import argparse
import importlib.util
import json
from pathlib import Path, PurePosixPath
import shutil
import sys
import tempfile

sys.dont_write_bytecode = True


PROFILE_SECTIONS = (
    "filament_list",
    "machine_model_list",
    "machine_list",
    "process_list",
)


def parse_args() -> argparse.Namespace:
    repo_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--profiles-dir",
        type=Path,
        default=repo_root / "app" / "src" / "test" / "resources" / "machine" / "catalogue",
        help="Directory containing recorded vendor indexes and fetched profile chains",
    )
    parser.add_argument(
        "--upstream-script",
        type=Path,
        default=repo_root / "app" / "src" / "main" / "cpp" / "orcaslicer" / "scripts"
        / "orca_extra_profile_check.py",
        help="Pinned Orca profile checker to wrap",
    )
    return parser.parse_args()


def load_upstream(path: Path):
    if not path.is_file():
        raise ValueError(f"Pinned upstream checker not found: {path}")
    spec = importlib.util.spec_from_file_location("orca_extra_profile_check", path)
    if spec is None or spec.loader is None:
        raise ValueError(f"Could not load pinned upstream checker: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_json(path: Path, upstream):
    try:
        with path.open("r", encoding="utf-8") as source:
            return json.load(source, object_pairs_hook=upstream.no_duplicates_object_pairs_hook)
    except Exception as error:
        raise ValueError(f"{path}: {error}") from error


def is_safe_profile_path(value: str) -> bool:
    if not value or "\\" in value or "\0" in value:
        return False
    path = PurePosixPath(value)
    return not path.is_absolute() and path.suffix.lower() == ".json" and all(
        part not in ("", ".", "..") for part in path.parts
    )


def discover_indexes(profiles_dir: Path) -> list[tuple[str, Path, Path]]:
    indexes: list[tuple[str, Path, Path]] = []
    for path in sorted(profiles_dir.glob("*.json")):
        indexes.append((path.stem, path, profiles_dir / path.stem))
    for path in sorted(profiles_dir.glob("*/index.json")):
        indexes.append((path.parent.name, path, path.parent))
    return indexes


def normalize_snapshot(profiles_dir: Path, output_dir: Path, upstream) -> tuple[list[str], int]:
    errors = 0
    vendors: list[str] = []
    seen_vendors: set[str] = set()
    for location_vendor, index_path, source_vendor_dir in discover_indexes(profiles_dir):
        try:
            index = load_json(index_path, upstream)
        except ValueError as error:
            print(f"[ERROR] {error}")
            errors += 1
            continue
        if not isinstance(index, dict):
            print(f"[ERROR] Vendor index must be an object: {index_path}")
            errors += 1
            continue
        vendor = str(index.get("name", "")).strip()
        if not vendor:
            print(f"[ERROR] Vendor index has no name: {index_path}")
            errors += 1
            continue
        if vendor != location_vendor:
            print(f"[ERROR] Vendor index name '{vendor}' does not match snapshot location '{location_vendor}'")
            errors += 1
            continue
        if vendor in seen_vendors:
            print(f"[ERROR] Duplicate vendor snapshot: {vendor}")
            errors += 1
            continue
        seen_vendors.add(vendor)
        vendors.append(vendor)

        target_vendor_dir = output_dir / vendor
        normalized = dict(index)
        for section in PROFILE_SECTIONS:
            entries = index.get(section, [])
            if not isinstance(entries, list):
                print(f"[ERROR] {index_path}: {section} must be an array")
                errors += 1
                normalized[section] = []
                continue
            included_entries = []
            declared_paths: set[str] = set()
            for entry in entries:
                if not isinstance(entry, dict):
                    print(f"[ERROR] {index_path}: invalid {section} entry")
                    errors += 1
                    continue
                name = str(entry.get("name", "")).strip()
                sub_path = str(entry.get("sub_path", "")).strip()
                if not name or not is_safe_profile_path(sub_path):
                    print(f"[ERROR] {index_path}: invalid {section} name or sub_path")
                    errors += 1
                    continue
                if sub_path in declared_paths:
                    print(f"[ERROR] {index_path}: duplicate {section} path '{sub_path}'")
                    errors += 1
                    continue
                declared_paths.add(sub_path)
                source_path = source_vendor_dir.joinpath(*PurePosixPath(sub_path).parts)
                if source_path.is_file():
                    included_entries.append(entry)
            normalized[section] = included_entries

        if source_vendor_dir.is_dir():
            for source_path in sorted(source_vendor_dir.rglob("*.json")):
                if source_path == index_path:
                    continue
                try:
                    profile = load_json(source_path, upstream)
                except ValueError as error:
                    print(f"[ERROR] {error}")
                    errors += 1
                    continue
                if not isinstance(profile, dict) or not isinstance(profile.get("name"), str) \
                        or not profile["name"].strip():
                    print(f"[ERROR] {source_path}: profile must be an object with a name")
                    errors += 1
                    continue
                relative = source_path.relative_to(source_vendor_dir)
                relative_posix = relative.as_posix()
                if not is_safe_profile_path(relative_posix):
                    print(f"[ERROR] Unsafe recorded profile path: {source_path}")
                    errors += 1
                    continue
                target_path = target_vendor_dir / relative
                target_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source_path, target_path)

        (output_dir / f"{vendor}.json").write_text(
            json.dumps(normalized, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    return vendors, errors


def run_upstream_checks(profiles_dir: Path, vendors: list[str], upstream) -> tuple[int, int]:
    errors = 0
    warnings = 0
    for vendor in vendors:
        vendor_dir = profiles_dir / vendor
        count, new_warnings = upstream.check_name_consistency(profiles_dir, vendor)
        errors += count
        warnings += new_warnings
        errors += upstream.check_filament_compatible_printers(vendor_dir / "filament")
        errors += upstream.check_filament_id(vendor, vendor_dir / "filament")
        warnings += upstream.check_obsolete_keys(profiles_dir, vendor)
    return errors, warnings


def main() -> int:
    args = parse_args()
    profiles_dir = args.profiles_dir.resolve()
    if not profiles_dir.is_dir():
        print(f"[ERROR] Profile snapshot directory not found: {profiles_dir}")
        return 2
    try:
        upstream = load_upstream(args.upstream_script.resolve())
    except ValueError as error:
        print(f"[ERROR] {error}")
        return 2

    with tempfile.TemporaryDirectory(prefix="orca-profile-check-") as temp:
        normalized_dir = Path(temp)
        vendors, errors = normalize_snapshot(profiles_dir, normalized_dir, upstream)
        upstream_errors, warnings = run_upstream_checks(normalized_dir, vendors, upstream)
        errors += upstream_errors

    print(f"Validated {len(vendors)} vendor snapshots: {errors} errors, {warnings} warnings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
