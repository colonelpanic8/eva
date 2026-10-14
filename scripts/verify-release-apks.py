#!/usr/bin/env python3
"""Verify phone/watch release identities before either artifact is published."""

import re
import subprocess
import sys


def verify(apksigner: str, aapt: str, version: str, code: str, certificate: str, apk: str) -> None:
    signatures = subprocess.check_output(
        [apksigner, "verify", "--verbose", "--print-certs", apk], text=True
    )
    certificates = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", signatures)
    if [value.lower() for value in certificates] != [certificate.lower()]:
        raise ValueError(f"{apk}: signer must match the configured release keystore alias")
    badging = subprocess.check_output([aapt, "dump", "badging", apk], text=True)
    package = re.search(r"^package: (.+)$", badging, re.MULTILINE)
    attributes = dict(re.findall(r"(\w+)='([^']*)'", package[1])) if package else {}
    expected = {"name": "com.colonelpanic.eva", "versionCode": code, "versionName": version}
    for key, value in expected.items():
        if attributes.get(key) != value:
            raise ValueError(f"{apk}: {key} is {attributes.get(key)!r}, expected {value!r}")
    print(f"Verified {apk}: {version} ({code}), certificate SHA-256 {certificate}")


if __name__ == "__main__":
    if len(sys.argv) != 8:
        sys.exit("Usage: verify-release-apks.py APKSIGNER AAPT VERSION CODE CERTIFICATE PHONE_APK WATCH_APK")
    try:
        for path in sys.argv[6:]:
            verify(*sys.argv[1:6], path)
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
