"""Fetch pinned upstream artifacts and reproduce the native/Java runtime. Python 3.11+."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, sys, urllib.request, zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SOURCES = {
    'sherpa-onnx-static-link-onnxruntime-1.13.8.aar': (
        'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar',
        'b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471'),
    'qnn-streaming.apk': (
        'https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/qnn-asr/1.13.8/sherpa-onnx-1.13.8-qnn-arm64-v8a-asr-zh_en-x_asr_streaming_zipformer_transducer_punct_2026_06_05_chunk_480ms.apk',
        'a06243526c3613a5edd73353cab6347901f38d589eb640a11a8e74356d59a173'),
    'onnxruntime-android-1.28.0.aar': (
        'https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.28.0/onnxruntime-android-1.28.0.aar',
        'f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9'),
    'geniex-android-0.8.0.aar': (
        'https://repo.maven.apache.org/maven2/com/qualcomm/qti/geniex-android/0.8.0/geniex-android-0.8.0.aar',
        '330d4d20ca27b449ba3e1b588fa2e964124e1682c01502952b483875d902dcc9'),
    'qnn-runtime-2.51.0.aar': (
        'https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.51.0/qnn-runtime-2.51.0.aar',
        '6388face1d5aa5e8958d745e5a547e92b5bec6fc332dfb1b438b1e17ffa02861'),
}

def digest(path):
    with path.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()

def fetch(cache, name, url, expected):
    path = cache / name
    if not path.exists():
        temporary = path.with_suffix(path.suffix + '.part')
        print('Downloading', name, flush=True)
        request = urllib.request.Request(url, headers={'User-Agent': 'InstantCaption-build/1.14'})
        with urllib.request.urlopen(request, timeout=90) as source, temporary.open('wb') as out:
            shutil.copyfileobj(source, out, 1024 * 1024)
        if digest(temporary) != expected: raise RuntimeError('SHA-256 mismatch: ' + name)
        temporary.replace(path)
    if digest(path) != expected: raise RuntimeError('Cached SHA-256 mismatch: ' + name)
    return path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--portable', action='store_true', help='Omit proprietary QNN/GenieX native libraries and use upstream CPU runtimes')
    parser.add_argument('--cache', type=Path, default=ROOT / '.runtime-cache')
    args = parser.parse_args(); cache = args.cache.resolve(); cache.mkdir(parents=True, exist_ok=True)
    artifacts = {n: fetch(cache, n, *source) for n, source in SOURCES.items() if not (args.portable and n in ('qnn-streaming.apk', 'qnn-runtime-2.51.0.aar'))}
    libs = ROOT / 'app/libs'; libs.mkdir(parents=True, exist_ok=True)
    native = ROOT / 'app/src/npu/jniLibs/arm64-v8a'; native.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(artifacts['sherpa-onnx-static-link-onnxruntime-1.13.8.aar']) as source, zipfile.ZipFile(libs / 'sherpa-onnx-1.13.8-api.aar', 'w', zipfile.ZIP_DEFLATED) as target:
        for name in ('AndroidManifest.xml', 'classes.jar', 'R.txt'): target.writestr(source.getinfo(name), source.read(name))
    for name in ('sherpa-onnx-static-link-onnxruntime-1.13.8.aar', 'onnxruntime-android-1.28.0.aar'):
        shutil.copyfile(artifacts[name], libs / name)
    if not args.portable:
        with zipfile.ZipFile(artifacts['qnn-streaming.apk']) as source:
            for name in ('libsherpa-onnx-jni.so', 'libonnxruntime.so'):
                (native / name).write_bytes(source.read('lib/arm64-v8a/' + name))
    import prepare_translation_runtime, prepare_qwen_runtime, prepare_litert_runtime
    prepare_translation_runtime.CACHE = cache
    prepare_qwen_runtime.CACHE = cache
    if not args.portable: prepare_translation_runtime.main()
    prepare_qwen_runtime.main(native_libraries=not args.portable)
    prepare_litert_runtime.CACHE = cache
    if not args.portable: prepare_litert_runtime.main()
    import prepare_cpu_runtime
    prepare_cpu_runtime.main([])
    if not args.portable: artifacts.update({n: cache/n for n in prepare_litert_runtime.ARTIFACTS})
    all_sources={**SOURCES,**prepare_litert_runtime.ARTIFACTS}
    inventory = {'build': 'portable' if args.portable else 'npu',
                 'artifacts': {n: {'url': all_sources[n][0], 'sha256': all_sources[n][1]} for n in artifacts},
                 'preparedNpuNative': {} if args.portable else {p.name: digest(p) for p in sorted(native.glob('*.so'))}}
    (libs / 'runtime-lock.json').write_text(json.dumps(inventory, indent=2) + '\n', encoding='utf8')
    print('Verified and prepared all runtime artifacts. Model weights are downloaded separately.')

if __name__ == '__main__': main()
