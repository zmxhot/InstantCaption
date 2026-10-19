"""Inspect an APK's packaged runtimes and ELF PT_LOAD alignment without external libraries."""
import argparse, hashlib, json, struct, zipfile
from pathlib import Path

def inspect(path, portable=False):
    natives=[]
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not name.startswith('lib/') or not name.endswith('.so'):continue
            data=archive.read(name)
            if data[:4]!=b'\x7fELF':raise RuntimeError('Invalid ELF: '+name)
            machine=struct.unpack_from('<H',data,18)[0]
            # Qualcomm's Hexagon DSP skeleton is loaded by the DSP, not Android's ARM loader.
            if machine!=183:
                if machine==164 and not portable:continue
                raise RuntimeError('Unexpected native architecture: '+name)
            if portable and any(x in name.lower() for x in ['qnn','geniex','npu_jni','litertdispatch','litertcompilerplugin','litertlm_jni']):raise RuntimeError('Vendor NPU runtime in portable APK: '+name)
            off=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54);alignments=[]
            for i in range(count):
                header=struct.unpack_from('<IIQQQQQQ',data,off+i*size)
                if header[0]==1:
                    alignments.append(header[7])
                    if header[7]<16384 or (header[2]-header[3])%16384:raise RuntimeError('Not 16 KB aligned: '+name)
            natives.append({'file':name,'loadAlignments':alignments,'sha256':hashlib.sha256(data).hexdigest()})
        required=['assets/licenses/LLAMA-CPP-LICENSE.txt','assets/licenses/HY-MT2-LICENSE.txt','assets/licenses/GEMMA3-NOTICE.txt','assets/licenses/GENIEX-BSD-3-Clause.txt','assets/licenses/FunASR-MODEL-LICENSE.txt','assets/licenses/CC-BY-4.0.txt','assets/X-ASR-LICENSE.txt']
        for name in required:
            if name not in archive.namelist():raise RuntimeError('Missing notice: '+name)
        manifest=json.loads(archive.read('assets/licenses/runtime-license-sources.json'))
        for name,entry in manifest['files'].items():
            if hashlib.sha256(archive.read('assets/licenses/'+name)).hexdigest()!=entry['sha256']:
                raise RuntimeError('Packaged license snapshot mismatch: '+name)
        if portable:
            # DEX descriptors survive shrinking/packing; the portable stub has no LiteRT API dependency.
            for name in archive.namelist():
                if name.endswith('.dex') and b'Lcom/google/ai/edge/litertlm/Engine;' in archive.read(name):
                    raise RuntimeError('LiteRT-LM API unexpectedly present in portable APK')
    if not natives:raise RuntimeError('No ARM64 runtime')
    return {'apk':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'portable':portable,'elf16KbAlignment':True,'native':natives}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('apk',type=Path);parser.add_argument('--portable',action='store_true');args=parser.parse_args()
    print(json.dumps(inspect(args.apk,args.portable),indent=2))
