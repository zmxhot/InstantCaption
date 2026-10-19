"""Prepare the Java JNI binding for the existing sherpa ORT 1.28.2 runtime.

Microsoft publishes Android 1.28.0, while sherpa ships 1.28.2. Both use C API
version 28, but ELF symbol versioning binds the JNI entry point to 1.28.0.
Adjust ONLY the JNI VERS requirement and its ELF hash, retain the shared runtime.
The input is the official unmodified Maven AAR; SHA-256 is pinned.
"""
from pathlib import Path
import io, struct, zipfile, urllib.request, hashlib, json


ROOT=Path(__file__).resolve().parent
CACHE=ROOT/'.runtime-cache'
SHA256='f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9'
URL='https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.28.0/onnxruntime-android-1.28.0.aar'
def elf_hash(value):
    h=0
    for b in value:
        h=(h<<4)+b
        g=h&0xf0000000
        if g: h^=g>>24
        h&=~g
    return h
def patch_version(data):
    out=bytearray(data)
    assert data[:6]==b'\x7fELF\x02\x01', 'Expected little-endian ELF64'
    shoff=struct.unpack_from('<Q',data,40)[0];size,count=struct.unpack_from('<HH',data,58)
    sections=[struct.unpack_from('<IIQQQQIIQQ',data,shoff+i*size) for i in range(count)]
    versions=next(s for s in sections if s[1]==0x6ffffffe)
    strings=sections[versions[6]];base=versions[4];offset=0;found=0
    for _ in range(versions[7]):
        version,count,filename,aux,next_need=struct.unpack_from('<HHIII',data,base+offset)
        item=offset+aux
        for __ in range(count):
            old_hash,flags,other,name,next_aux=struct.unpack_from('<IHHII',data,base+item)
            start=strings[4]+name;text=data[start:data.index(b'\0',start)].decode('ascii')
            if text=='VERS_1.28.0':
                assert old_hash==elf_hash(text.encode())
                new=b'VERS_1.28.2';pos=strings[4]+name
                assert data[pos:pos+len(new)]==text.encode()
                out[pos:pos+len(new)]=new
                struct.pack_into('<I',out,base+item,elf_hash(new));found+=1
            item+=next_aux
        offset+=next_need
    assert found==1,found
    return bytes(out)
def main():
    CACHE.mkdir(parents=True,exist_ok=True);source=CACHE/'onnxruntime-android-1.28.0.aar'
    if not source.exists():
        temporary=source.with_suffix('.part');urllib.request.urlretrieve(URL,temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest()!=SHA256:raise RuntimeError('ORT checksum mismatch')
        temporary.replace(source)
    if hashlib.sha256(source.read_bytes()).hexdigest()!=SHA256:raise RuntimeError('ORT checksum mismatch')
    output=ROOT/'app/libs/onnxruntime-android-1.28.0-api.aar'
    with zipfile.ZipFile(source) as archive,zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as target:
        for name in archive.namelist():
            if name.startswith('jni/') and name!='jni/arm64-v8a/libonnxruntime4j_jni.so':continue
            data=archive.read(name)
            if name.endswith('libonnxruntime4j_jni.so'):data=patch_version(data)
            target.writestr(archive.getinfo(name),data)
    provenance={'source':URL,'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'outputSha256':hashlib.sha256(output.read_bytes()).hexdigest(),'change':'JNI ELF OrtGetApiBase version requirement 1.28.0→1.28.2; C API 28 unchanged; original sherpa shared runtime retained'}
    (ROOT/'app/libs/onnxruntime-api-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf8')
    print(json.dumps(provenance))
if __name__=='__main__':main()
