import importlib.util
import pathlib
import struct
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('page_size', pathlib.Path(__file__).with_name('validate-native-page-size.py'))
page_size = importlib.util.module_from_spec(spec)
spec.loader.exec_module(page_size)


def elf(alignment=16384, relro_end=16384, shared=False):
    data = bytearray(512)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<H', data, 18, 183)
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 3)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 6, 0, 0, 0, 512, 32768, alignment)
    struct.pack_into('<IIQQQQQQ', data, 120, 0x6474e552, 4, 0, 0, 0, 128, relro_end, 1)
    struct.pack_into('<IIQQQQQQ', data, 176, 2, 6, 240, 240, 0, 48, 48, 8)
    struct.pack_into('<qQ', data, 240, 5, 320)
    struct.pack_into('<qQ', data, 256, 1, 0)
    name = b'libc++_shared.so\0' if shared else b'libc.so\0'
    data[320:320 + len(name)] = name
    return data


class NativePageSizeTest(unittest.TestCase):
    def test_compressed_manifest_extraction_true_passes(self):
        page_size.validate_manifest('A: android:extractNativeLibs(0x010104ea)=(type 0x12)0xffffffff', True)

    def test_compressed_manifest_extraction_false_fails(self):
        with self.assertRaisesRegex(ValueError, 'extractNativeLibs=true'):
            page_size.validate_manifest('A: android:extractNativeLibs(0x010104ea)=(type 0x12)0x0', True)

    def test_compressed_manifest_extraction_absent_fails(self):
        with self.assertRaisesRegex(ValueError, 'extractNativeLibs=true'):
            page_size.validate_manifest('application', True)

    def test_aligned_static_runtime_passes(self):
        self.assertEqual(['libc.so'], page_size.validate_elf(elf()))

    def test_old_load_alignment_fails(self):
        with self.assertRaisesRegex(ValueError, 'LOAD alignment'):
            page_size.validate_elf(elf(alignment=4096))

    def test_old_relro_alignment_fails(self):
        with self.assertRaisesRegex(ValueError, 'GNU_RELRO'):
            page_size.validate_elf(elf(relro_end=4096))

    def test_shared_runtime_dependency_fails(self):
        with self.assertRaisesRegex(ValueError, 'statically'):
            page_size.validate_elf(elf(shared=True))

    def test_compressed_apk_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / 'test.apk'
            with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('lib/arm64-v8a/libprusaslicer-jni.so', elf())
            page_size.validate_apk(apk)

    def test_unaligned_uncompressed_apk_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libprusaslicer-jni.so', elf())
            with self.assertRaisesRegex(ValueError, 'ZIP-aligned'):
                page_size.validate_apk(apk)

    def test_additional_runtime_in_apk_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / 'test.apk'
            with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('lib/arm64-v8a/libprusaslicer-jni.so', elf())
                archive.writestr('lib/arm64-v8a/libc++_shared.so', elf())
            with self.assertRaisesRegex(ValueError, 'single ARM64'):
                page_size.validate_apk(apk)


if __name__ == '__main__':
    unittest.main()
