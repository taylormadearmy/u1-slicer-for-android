#!/usr/bin/env python3
"""Check every ARM64 library in the actual APK, including its ZIP placement."""
import argparse
import re
import struct
import subprocess
import zipfile

PAGE_SIZE = 16384


def validate_elf(data):
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError('Expected a little-endian ELF64 library')
    if struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('Expected ARM64')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    phsize, phcount = struct.unpack_from('<HH', data, 54)
    headers = [struct.unpack_from('<IIQQQQQQ', data, phoff + i * phsize)
               for i in range(phcount)]
    loads = [header for header in headers if header[0] == 1]
    if not loads:
        raise ValueError('No LOAD segments')
    for _, _, offset, address, _, _, _, alignment in loads:
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            raise ValueError('LOAD alignment is below 16KB or not a power of two')
        if offset % alignment != address % alignment:
            raise ValueError('LOAD file and virtual offsets disagree')
    relro = [header for header in headers if header[0] == 0x6474e552]
    if not relro or any((header[3] + header[6]) % PAGE_SIZE for header in relro):
        raise ValueError('GNU_RELRO does not end on a 16KB boundary')
    dynamics = [header for header in headers if header[0] == 2]
    if len(dynamics) != 1:
        raise ValueError('Expected one DYNAMIC segment')
    dynamic = dynamics[0]
    tags = []
    for position in range(dynamic[2], dynamic[2] + dynamic[5], 16):
        tag, value = struct.unpack_from('<qQ', data, position)
        if tag == 0:
            break
        tags.append((tag, value))
    string_address = next(value for tag, value in tags if tag == 5)
    string_load = next(header for header in loads
                       if header[3] <= string_address < header[3] + header[5])
    string_offset = string_load[2] + string_address - string_load[3]
    needed = []
    for tag, value in tags:
        if tag == 1:
            start = string_offset + value
            needed.append(data[start:data.index(b'\0', start)].decode('ascii'))
    if 'libc++_shared.so' in needed:
        raise ValueError('JNI must link the C++ runtime statically')
    return needed


def validate_apk(path):
    with zipfile.ZipFile(path) as archive, open(path, 'rb') as raw:
        libraries = [entry for entry in archive.infolist()
                     if entry.filename.startswith('lib/') and entry.filename.endswith('.so')]
        if [entry.filename for entry in libraries] != ['lib/arm64-v8a/libprusaslicer-jni.so']:
            raise ValueError('Expected exactly the single ARM64 JNI library, without a shared C++ runtime')
        for entry in libraries:
            raw.seek(entry.header_offset)
            local = raw.read(30)
            name_size, extra_size = struct.unpack_from('<HH', local, 26)
            data_offset = entry.header_offset + 30 + name_size + extra_size
            if entry.compress_type == zipfile.ZIP_STORED and data_offset % PAGE_SIZE:
                raise ValueError('Uncompressed JNI library is not ZIP-aligned to 16KB')
            needed = validate_elf(archive.read(entry))
            print(f'PASS {entry.filename}: 16KB LOAD/RELRO, ZIP method {entry.compress_type}, dependencies {needed}')
        return any(entry.compress_type != zipfile.ZIP_STORED for entry in libraries)


def validate_manifest(manifest, compressed):
    if compressed:
        extraction = re.search(r'android:extractNativeLibs[^\n]*\(type 0x12\)(0x[0-9a-fA-F]+)', manifest)
        if not extraction or int(extraction.group(1), 16) != 0xffffffff:
            raise ValueError('Compressed native libraries require extractNativeLibs=true in the actual manifest')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk')
    parser.add_argument('--elf', action='store_true', help='Validate an unpackaged JNI ELF instead of an APK')
    parser.add_argument('--aapt', help='SDK aapt executable required to check an APK manifest')
    args = parser.parse_args()
    try:
        if args.elf:
            with open(args.apk, 'rb') as library:
                print(f'PASS ELF: {validate_elf(library.read())}')
        else:
            compressed = validate_apk(args.apk)
            if not args.aapt:
                raise ValueError('Supply --aapt to validate the actual APK manifest')
            manifest = subprocess.check_output([args.aapt, 'dump', 'xmltree', args.apk, 'AndroidManifest.xml'], text=True)
            validate_manifest(manifest, compressed)
            print('PASS actual APK native extraction manifest')
    except (ValueError, StopIteration, struct.error, zipfile.BadZipFile, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'FAIL: {error}\n')
