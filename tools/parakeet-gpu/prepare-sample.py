#!/usr/bin/env python3
"""Adapt the pinned official full ASR sample to Lacan's existing toolchain, CPU/GPU only."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--source', type=Path, required=True, help='checkout from fetch-upstream.sh')
p.add_argument('--destination', type=Path, default=Path('/tmp/lacan-parakeet-full-sample'))
a = p.parse_args()
lock = json.loads(Path(__file__).with_name('artifacts.lock.json').read_text())
revision = subprocess.check_output(['git', '-C', str(a.source), 'rev-parse', 'HEAD'], text=True).strip()
if revision != lock['sample_commit']:
    raise SystemExit('Unexpected source revision')
if a.destination.exists():
    raise SystemExit('Destination exists; use a fresh directory')
source = a.source / 'samples/litert/speech_recognition/AndroidApp'
shutil.copytree(source, a.destination)
# Optional NPU modules are not in the public sample checkout. They are unnecessary for GPU.
settings = a.destination / 'settings.gradle.kts'
settings.write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "ParakeetFullSample"
include(":app")
''')
versions = a.destination / 'gradle/libs.versions.toml'
versions.write_text(versions.read_text().replace('agp = "9.1.1"', 'agp = "8.13.2"').replace('kotlin = "2.2.10"', 'kotlin = "2.2.21"'))
build = a.destination / 'app/build.gradle.kts'
s = build.read_text().replace('  compileSdk = 35', '  compileSdk = 36\n  ndkVersion = \"27.2.12479018\"')
s = s.replace('id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"', 'id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"')
s = s.replace('  implementation(project(":litert_npu_runtime_libraries:runtime_strings"))\n', '')
start = s.index('  bundle {')
end = s.index('\n}', start)
s = s[:start] + s[end:]
# Remove mutable frontend JAR download task; fetch and verify exactly once below.
start = s.index('tasks.register<de.undercouch.gradle.tasks.download.Download>')
end = s.index('dependencies {', start)
s = s[:start] + s[end:]
build.write_text(s)
# Pin all model/tokenizer network URLs; expose only the exact multilingual TDT v3.
metadata = a.destination / 'app/src/main/assets/model_metadata.json'
config = json.loads(metadata.read_text())['parakeet-tdt-0.6b-v3']
config['modelRemoteUrl'] = f"https://huggingface.co/{lock['model_repository']}/resolve/{lock['model_revision']}/parakeet_tdt_0.6b_v3_5s_i8.tflite"
config['tokenizerUrl'] = 'https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/resolve/541d1f99c6b0c3cd0b11a95167540bb8edefd82b/tokenizer.json'
metadata.write_text(json.dumps({'parakeet-tdt-0.6b-v3': config}, indent=2) + '\n')
jar = a.destination / 'app/libs/jlibrosa-1.1.8-SNAPSHOT-jar-with-dependencies.jar'
jar.parent.mkdir(parents=True, exist_ok=True)
url = 'https://github.com/Subtitle-Synchronizer/jlibrosa/raw/master/binaries/jlibrosa-1.1.8-SNAPSHOT-jar-with-dependencies.jar'
with urllib.request.urlopen(url, timeout=60) as response:
    data = response.read(16 * 1024 * 1024)
if hashlib.sha256(data).hexdigest() != 'dc6ec9537f6e1e4f300b4021e214cc7f22bc661161d3a64ecd57b6a113a6a077':
    raise SystemExit('Unexpected JLibrosa JAR hash')
jar.write_bytes(data)
print(a.destination)
print('Adapted source; no models downloaded, build or device action executed.')
