"""Reproduce the official GenieX Java API + QAIRT NPU libraries used by this app.

Run from the project root with Python 3. No model weights are bundled in the APK.
The separate qwen-translation.json catalog pins all downloadable model files.
"""
from pathlib import Path
import hashlib
import json
import urllib.request
import zipfile

VERSION = "0.8.0"
SHA256 = "330d4d20ca27b449ba3e1b588fa2e964124e1682c01502952b483875d902dcc9"
ROOT = Path(__file__).resolve().parent
CACHE = ROOT / '.runtime-cache'
URL = f"https://repo.maven.apache.org/maven2/com/qualcomm/qti/geniex-android/{VERSION}/geniex-android-{VERSION}.aar"
QNN_VERSION = "2.51.0"
QNN_SHA256 = "6388face1d5aa5e8958d745e5a547e92b5bec6fc332dfb1b438b1e17ffa02861"
QNN_URL = f"https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/{QNN_VERSION}/qnn-runtime-{QNN_VERSION}.aar"
LIBRARIES = [
    "libnpu_jni.so", "libgeniex.so", "libgeniex_core.so", "libgeniex_vlm.so",
    "libgeniex_plugin_qairt.so", "libgeniex-proc.so", "libgeniex-proc-vision.so",
    "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnHtpV79Skel.so",
    "libQnnHtpV79Stub.so", "libQnnHtpV81Stub.so", "libQnnHtpV81Skel.so",
    "libQnnSystem.so", "libQnnHtpNetRunExtensions.so",
]
QNN_LIBRARIES = ["libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so"] + [
    f"libQnnHtpV{arch}{kind}.so" for arch in (68, 69, 73, 75, 79, 81) for kind in ("Stub", "Skel")]


def main(native_libraries=True):
    cache = CACHE
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f"geniex-android-{VERSION}.aar"
    if not archive.exists():
        temporary = archive.with_suffix(".part")
        urllib.request.urlretrieve(URL, temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != SHA256:
            raise RuntimeError("GenieX download checksum mismatch")
        temporary.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("Cached GenieX checksum mismatch")
    native = ROOT / "app/src/npu/jniLibs/arm64-v8a"
    native.mkdir(parents=True, exist_ok=True)
    hashes = {}
    with zipfile.ZipFile(archive) as source:
        with zipfile.ZipFile(ROOT / f"app/libs/geniex-android-{VERSION}-api.aar", "w", zipfile.ZIP_DEFLATED) as target:
            for name in ("AndroidManifest.xml", "classes.jar", "R.txt"):
                target.writestr(source.getinfo(name), source.read(name))
        for name in (LIBRARIES if native_libraries else []):
            data = source.read(f"jni/arm64-v8a/{name}")
            (native / name).write_bytes(data)
            hashes[name] = hashlib.sha256(data).hexdigest()
    if native_libraries:
        # One coherent official QNN distribution supplies the host runtime and
        # all DSP generations; do not mix an older DSP library with newer hosts.
        qnn = cache / f"qnn-runtime-{QNN_VERSION}.aar"
        if not qnn.exists():
            partial = qnn.with_suffix('.part')
            urllib.request.urlretrieve(QNN_URL, partial)
            if hashlib.sha256(partial.read_bytes()).hexdigest() != QNN_SHA256:
                raise RuntimeError('QNN runtime download checksum mismatch')
            partial.replace(qnn)
        if hashlib.sha256(qnn.read_bytes()).hexdigest() != QNN_SHA256:
            raise RuntimeError('Cached QNN runtime checksum mismatch')
        with zipfile.ZipFile(qnn) as source:
            for name in QNN_LIBRARIES:
                data = source.read(f'jni/arm64-v8a/{name}')
                (native / name).write_bytes(data)
                hashes[name] = hashlib.sha256(data).hexdigest()
    (cache / "runtime-sha256.json").write_text(json.dumps(hashes, indent=2), encoding="utf-8")
    print('Prepared GenieX Java API' + (' and QAIRT 2.51 NPU libraries.' if native_libraries else ' only; no Qualcomm native runtime.'))


if __name__ == "__main__":
    main()
