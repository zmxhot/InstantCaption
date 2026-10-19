"""Prepare pinned LiteRT-LM API and standalone NPU libraries, without Play services."""
from pathlib import Path
import hashlib, urllib.request, zipfile

ROOT=Path(__file__).resolve().parent
CACHE=ROOT/'.runtime-cache'
ARTIFACTS={
 'litertlm-android-0.18.0.aar':('https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.aar','706cbb8a87739b2818111bf3a19a78b8f7781586d10f80adf3415581fab7595f'),
 'litert-npu-runtime-google-tensor-2.3.0.aar':('https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-npu-runtime-google-tensor/2.3.0/litert-npu-runtime-google-tensor-2.3.0.aar','5f38ab3de4b99ef75edf13c595cf0c090b10a82d9697f5b55703409757e786f7'),
 'litert-npu-runtime-qualcomm-2.3.0.aar':('https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-npu-runtime-qualcomm/2.3.0/litert-npu-runtime-qualcomm-2.3.0.aar','55aa22e89b7decb9e2f56548188b9e8c42cc8f7a828e2ec4a17715079b313679'),
 'litert-npu-runtime-mediatek-2.3.0.aar':('https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-npu-runtime-mediatek/2.3.0/litert-npu-runtime-mediatek-2.3.0.aar','949fcd3a40d597f07473eb8d6b4d89ab6343b5a8fd2d21c118e2855e613c4587'),
}

def main(native_libraries=True):
    CACHE.mkdir(parents=True,exist_ok=True);native=ROOT/'app/src/npu/jniLibs/arm64-v8a'
    for name,(url,expected) in ARTIFACTS.items():
        if not native_libraries and not name.startswith('litertlm-'):continue
        path=CACHE/name
        if not path.exists():
            partial=path.with_suffix('.part');urllib.request.urlretrieve(url,partial)
            if hashlib.sha256(partial.read_bytes()).hexdigest()!=expected:raise RuntimeError('LiteRT download checksum mismatch')
            partial.replace(path)
        if hashlib.sha256(path.read_bytes()).hexdigest()!=expected:raise RuntimeError('LiteRT cached checksum mismatch')
        with zipfile.ZipFile(path) as archive:
            if native_libraries and name.startswith('litert-npu-runtime-'):
                # Preserve the publisher's optional vendor-library declarations.
                # Native files are packaged separately, avoiding duplicate .so entries.
                manifest_name=name.replace('.aar','-manifest.aar')
                with zipfile.ZipFile(ROOT/'app/libs'/manifest_name,'w',zipfile.ZIP_DEFLATED) as manifest:
                    manifest.writestr('AndroidManifest.xml',archive.read('AndroidManifest.xml'))
            if name.startswith('litertlm-'):
                with zipfile.ZipFile(ROOT/'app/libs/litertlm-android-0.18.0-api.aar','w',zipfile.ZIP_DEFLATED) as api:
                    for entry in ('AndroidManifest.xml','classes.jar','R.txt'):
                        if entry in archive.namelist():api.writestr(archive.getinfo(entry),archive.read(entry))
            if native_libraries:
                for entry in archive.namelist():
                    if entry.startswith('jni/arm64-v8a/') and entry.endswith('.so'):
                        (native/entry.rsplit('/',1)[-1]).write_bytes(archive.read(entry))
    print('Prepared LiteRT-LM API'+(' and standalone NPU libraries' if native_libraries else ' only'))

if __name__=='__main__':main()
