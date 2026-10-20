#!/usr/bin/env python3
"""Derive runtime-only JLibrosa from the hash-pinned upstream fat JAR.

Upstream bundles an old JUnit/Hamcrest, which shadows our real test dependencies.
No DSP classes are changed; original license/notice resources are retained.
"""
from pathlib import Path
import hashlib
import zipfile

root = Path(__file__).resolve().parent.parent
source = root / 'app/libs/jlibrosa-1.1.8-SNAPSHOT-jar-with-dependencies.jar'
assert hashlib.sha256(source.read_bytes()).hexdigest() == 'dc6ec9537f6e1e4f300b4021e214cc7f22bc661161d3a64ecd57b6a113a6a077', 'Unexpected upstream JLibrosa JAR'
target = root / 'app/libs/jlibrosa-runtime.jar'
temporary = target.with_suffix('.jar.part')
try:
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(temporary, 'w') as dst:
        for entry in src.infolist():
            if not entry.filename.startswith(('junit/', 'org/junit/', 'org/hamcrest/')):
                dst.writestr(entry, src.read(entry))
    temporary.replace(target)
finally:
    temporary.unlink(missing_ok=True)
