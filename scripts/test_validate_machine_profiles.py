import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPO_ROOT = Path(__file__).resolve().parent.parent
VALIDATOR = REPO_ROOT / "scripts" / "validate-machine-profiles.py"
SNAPSHOTS = REPO_ROOT / "app" / "src" / "test" / "resources" / "machine" / "catalogue"


class ValidateMachineProfilesTest(unittest.TestCase):
    def run_validator(self, profiles_dir: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(VALIDATOR), "--profiles-dir", str(profiles_dir)],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_recorded_pinned_snapshots_pass_upstream_checks(self) -> None:
        result = self.run_validator(SNAPSHOTS)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Validated 4 vendor snapshots", result.stdout)
        self.assertIn("0 errors", result.stdout)

    def test_duplicate_json_key_fails_validation(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            profiles_dir = Path(temp)
            vendor_dir = profiles_dir / "Acme" / "machine"
            vendor_dir.mkdir(parents=True)
            (profiles_dir / "Acme.json").write_text(
                json.dumps(
                    {
                        "name": "Acme",
                        "version": "1.0.0.0",
                        "machine_model_list": [],
                        "machine_list": [
                            {"name": "Acme One", "sub_path": "machine/Acme One.json"}
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (vendor_dir / "Acme One.json").write_text(
                '{"type":"machine","name":"Acme One","name":"Acme Two"}',
                encoding="utf-8",
            )

            result = self.run_validator(profiles_dir)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("Duplicate key", result.stdout)

    def test_upstream_name_consistency_failure_is_propagated(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            profiles_dir = Path(temp)
            vendor_dir = profiles_dir / "Acme" / "machine"
            vendor_dir.mkdir(parents=True)
            (profiles_dir / "Acme.json").write_text(
                json.dumps(
                    {
                        "name": "Acme",
                        "version": "1.0.0.0",
                        "machine_model_list": [],
                        "machine_list": [
                            {"name": "Acme One", "sub_path": "machine/Acme One.json"}
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (vendor_dir / "Acme One.json").write_text(
                json.dumps({"type": "machine", "name": "Wrong name"}),
                encoding="utf-8",
            )

            result = self.run_validator(profiles_dir)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("name mismatch", result.stdout)

    def test_non_object_profile_reports_controlled_validation_error(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            profiles_dir = Path(temp)
            vendor_dir = profiles_dir / "Acme" / "machine"
            vendor_dir.mkdir(parents=True)
            (profiles_dir / "Acme.json").write_text(
                json.dumps(
                    {
                        "name": "Acme",
                        "version": "1.0.0.0",
                        "machine_model_list": [],
                        "machine_list": [
                            {"name": "Acme One", "sub_path": "machine/Acme One.json"}
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (vendor_dir / "Acme One.json").write_text("[]", encoding="utf-8")

            result = self.run_validator(profiles_dir)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("profile must be an object with a name", result.stdout)
        self.assertNotIn("Traceback", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
