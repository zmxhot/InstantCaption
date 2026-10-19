"""Build the pinned ARM64 CPU translator from source; no device-specific instruction baseline."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,urllib.request,zipfile
ROOT=Path(__file__).resolve().parent
COMMIT='620d2225cc436ab0b001e3200e590e3b286ef9ab'
SOURCE_SHA256='48b431f8984dcc79efd2589de85fc53e42c6193522c97641e8dbc4376ebb3a5a'
NDK='28.2.13676358';CMAKE='3.31.6'
def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--sdk',type=Path);args=parser.parse_args(argv)
    sdk=args.sdk or Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')))
    suffix='.exe' if os.name=='nt' else '';cmake=sdk/'cmake'/CMAKE/'bin'/('cmake'+suffix);ninja=sdk/'cmake'/CMAKE/'bin'/('ninja'+suffix)
    toolchain=sdk/'ndk'/NDK/'build/cmake/android.toolchain.cmake'
    if not all(x.is_file() for x in [cmake,ninja,toolchain]):raise RuntimeError(f'Install sdkmanager "ndk;{NDK}" "cmake;{CMAKE}" and set ANDROID_HOME')
    cache=ROOT/'.runtime-cache';cache.mkdir(exist_ok=True);archive=cache/f'llama-{COMMIT}.zip'
    url=f'https://codeload.github.com/ggml-org/llama.cpp/zip/{COMMIT}'
    if not archive.exists():
        part=archive.with_suffix('.part');urllib.request.urlretrieve(url,part)
        if hashlib.sha256(part.read_bytes()).hexdigest()!=SOURCE_SHA256:raise RuntimeError('llama.cpp source checksum mismatch')
        part.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=SOURCE_SHA256:raise RuntimeError('llama.cpp cached source checksum mismatch')
    source=cache/f'llama.cpp-{COMMIT}'
    if not source.exists():
        with zipfile.ZipFile(archive) as z:
            if any(n.startswith(('/','\\')) or '..' in Path(n).parts for n in z.namelist()):raise RuntimeError('Unsafe source path')
            z.extractall(cache)
    build=cache/'caption-cpu-arm64'
    subprocess.run([str(cmake),'-S',str(ROOT/'app/src/main/cpp'),'-B',str(build),'-G','Ninja',f'-DCMAKE_MAKE_PROGRAM={ninja}',f'-DCMAKE_TOOLCHAIN_FILE={toolchain}',f'-DLLAMA_SOURCE={source}','-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-29','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release'],check=True)
    subprocess.run([str(cmake),'--build',str(build),'--target','caption_cpu','-j',str(min(8,os.cpu_count() or 2))],check=True)
    native=ROOT/'app/src/cpu/jniLibs/arm64-v8a';native.mkdir(parents=True,exist_ok=True);target=native/'libcaption_cpu.so';shutil.copyfile(build/'libcaption_cpu.so',target)
    host='windows-x86_64' if os.name=='nt' else 'linux-x86_64';strip=sdk/'ndk'/NDK/'toolchains/llvm/prebuilt'/host/'bin'/('llvm-strip'+suffix)
    subprocess.run([str(strip),'--strip-debug',str(target)],check=True)
    licenses=ROOT/'app/src/main/assets/licenses';shutil.copyfile(source/'LICENSE',licenses/'LLAMA-CPP-LICENSE.txt')
    (ROOT/'app/libs/cpu-runtime-provenance.json').write_text(json.dumps({'source':url,'commit':COMMIT,'sourceSha256':SOURCE_SHA256,'ndk':NDK,'cmake':CMAKE,'abi':'arm64-v8a','androidApi':29,'nativeCpuFeatures':False,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()},indent=2)+'\n',encoding='utf8')
    print('Built pinned CPU translation runtime',target.stat().st_size,'bytes')
if __name__=='__main__':main()
