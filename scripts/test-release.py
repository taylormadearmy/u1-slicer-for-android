import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release_guard', Path(__file__).with_name('release.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class ReleaseGuardTest(unittest.TestCase):
    def test_rejects_actual_wrong_public_release_tag(self):
        with self.assertRaisesRegex(ValueError, 'Release tag points'):
            guard.verify_tag('e039db89', '712d9fc4')

    def test_accepts_exact_tag_commit(self):
        guard.verify_tag('31690560', '31690560')

    def test_installer_evidence_is_bound_to_apk_and_file(self):
        with tempfile.TemporaryDirectory() as temp:
            evidence = Path(temp) / 'completed.png'
            evidence.write_bytes(b'captured installer evidence')
            candidate = {'apkSha256': 'exact-apk-hash'}
            check = dict(apkSha256='exact-apk-hash', result='passed', playProtectEnabled=True,
                         method='files', device='approved-test-phone', evidence=str(evidence),
                         evidenceSha256=guard.digest(evidence))
            guard.validate_install(candidate, check)
            for changes in [dict(apkSha256='another-apk'), dict(result='blocked'),
                            dict(playProtectEnabled=False), dict(method='adb'), dict(device='')]:
                with self.subTest(changes=changes), self.assertRaises(ValueError):
                    guard.validate_install(candidate, check | changes)
            evidence.write_bytes(b'changed evidence')
            with self.assertRaisesRegex(ValueError, 'evidence is missing or has changed'):
                guard.validate_install(candidate, check)

    def test_certificate_change_blocks_release(self):
        with patch.object(guard, 'run', return_value='certificate SHA-256 digest: wrong'):
            with self.assertRaisesRegex(ValueError, 'certificate changed'):
                guard.inspect_apk('candidate.apk', '5.0.13')

    def test_dirty_tracked_source_blocks_preparation(self):
        with patch.object(guard, 'run', return_value=' M app/build.gradle'):
            with self.assertRaisesRegex(ValueError, 'Commit all tracked'):
                guard.clean_source()

    def test_wrong_remote_tag_never_creates_or_publishes_release(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            apk = directory / 'candidate.apk'
            apk.write_bytes(b'candidate')
            notes = directory / 'notes.md'
            notes.write_text('release notes')
            candidate = dict(version='5.0.14', sourceCommit='expected-source',
                             apk=str(apk), apkSha256=guard.digest(apk))
            guard.save(directory / 'candidate.json', candidate)
            guard.save(directory / 'installer-check.json', {})
            calls = []

            def execute(*args):
                calls.append(args)
                if args[:3] == ('gh', 'api', 'user'):
                    return 'taylormadearmy'
                if args[:3] == ('git', 'ls-remote', '--tags'):
                    return ''
                if args[:2] == ('gh', 'api') and args[2].endswith('/commits/v5.0.14'):
                    return 'wrong-source'
                return 'expected-source'

            with patch.object(guard, 'run', side_effect=execute), \
                    patch.object(guard, 'clean_source', return_value='expected-source'), \
                    patch.object(guard, 'inspect_apk'), patch.object(guard, 'validate_install'):
                with self.assertRaisesRegex(ValueError, 'Release tag points'):
                    guard.publish(SimpleNamespace(version='5.0.14', notes=str(notes)), directory)
            self.assertFalse(any(c[:2] == ('gh', 'release') for c in calls))
            self.assertIn(('git', 'tag', 'v5.0.14', 'expected-source'), calls)


if __name__ == '__main__':
    unittest.main()
