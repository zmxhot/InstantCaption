"""Import publicly downloadable publisher NPU targets; never bypass gated models."""
from pathlib import Path
import argparse, hashlib, json, re, shutil, urllib.request

ROOT=Path(__file__).resolve().parents[1]
REPO='litert-community/gemma-4-E2B-it-litert-lm'
TARGETS={'sm8750':'gemma-4-E2B-it_qualcomm_sm8750.litertlm',
         'tensor-g5':'gemma-4-E2B-it_Google_Tensor_G5.litertlm',
         'tensor-g6':'gemma-4-E2B-it_Google_Tensor_G6.litertlm'}

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'InstantCaption-catalog'}),timeout=90)
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--download',choices=list(TARGETS));args=parser.parse_args()
    with get('https://huggingface.co/api/models/'+REPO) as src:meta=json.load(src)
    if meta.get('gated'):raise RuntimeError('Publisher authorization required; not enabling anonymous downloads')
    revision=meta['sha']
    with get(f'https://huggingface.co/api/models/{REPO}/tree/{revision}?recursive=true') as src:files={x['path']:x for x in json.load(src)}
    assets=ROOT/'app/src/main/assets';catalog=json.loads((assets/'device-model-catalog.json').read_text(encoding='utf8'))
    for target,filename in TARGETS.items():
        item=files[filename];sha=item['lfs']['oid'];size=item['size']
        if not re.fullmatch('[a-f0-9]{64}',sha):raise RuntimeError('No publisher checksum')
        chip='SM8750' if target=='sm8750' else 'Tensor_'+target[-2:].upper()
        url=f'https://huggingface.co/{REPO}/resolve/{revision}/{filename}'
        spec={'id':'gemma4-e2b-npu','chipset':chip,'runtime':'litert-lm','entryFile':'model.litertlm',
              'source':f'https://huggingface.co/{REPO}/tree/{revision}','revision':revision,
              'files':[{'name':'model.litertlm','size':size,'sha256':sha}],
              'download':{'format':'raw','size':size,'sha256':sha,'urls':[{'name':'Hugging Face','url':url},
               {'name':'HF mirror · hf-mirror.com','url':url.replace('huggingface.co','hf-mirror.com')}]}}
        manifest=f'gemma4-e2b-npu-{target}.json';write(assets/manifest,spec)
        rule={'id':spec['id'],'task':'text','runtime':'litert-lm','manifest':manifest,
              'directory':'translation-gemma4-e2b-'+target,'priority':30,'minAndroid':35,'androidVersion':'15',
              'minRamBytes':11000000000,'nominalRamGb':12,'allowLowRam':False}
        profile=next((p for p in catalog['profiles'] if p['id']==target),None)
        if profile is None:
            profile={'id':target,'extends':'portable','npuChip':chip,'documentationBased':True,
                     'requiredLibraries':['liblitertlm_jni.so','libLiteRtDispatch_GoogleTensor.so'],'models':[]}
            catalog['profiles'].append(profile)
        elif 'liblitertlm_jni.so' not in profile['requiredLibraries']:
            profile['requiredLibraries']+=['liblitertlm_jni.so','libLiteRtDispatch_Qualcomm.so']
        profile['models']=[m for m in profile['models'] if m['id']!=spec['id']]+[rule]
        if target.startswith('tensor'):
            catalog['chipsets']=[c for c in catalog['chipsets'] if c['id']!=target]
            generation=target[-2:].upper()
            catalog['chipsets'].insert(0,{'id':target,'name':'Google Tensor '+generation,'profile':target,
                'match':{'soc':[f'(?:GOOGLE[ _])?TENSOR[ _]{generation}']}})
        if args.download==target:
            cache=ROOT/'.runtime-cache/litert-models';cache.mkdir(parents=True,exist_ok=True);path=cache/filename
            if not path.exists():
                print('Downloading',target,flush=True)
                part=path.with_suffix('.part')
                with get(url) as src,part.open('wb') as out:shutil.copyfileobj(src,out,1024*1024)
                with part.open('rb') as src:actual=hashlib.file_digest(src,'sha256').hexdigest()
                if part.stat().st_size!=size or actual!=sha:raise RuntimeError('Publisher model checksum mismatch')
                part.replace(path)
            with path.open('rb') as src:actual=hashlib.file_digest(src,'sha256').hexdigest()
            if actual!=sha:raise RuntimeError('Cached model checksum mismatch')
            print('Verified',target,flush=True)
    write(assets/'device-model-catalog.json',catalog)
    print('Prepared three public NPU text model targets from publisher revision',revision)

if __name__=='__main__':main()
