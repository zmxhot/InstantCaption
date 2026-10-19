"""Pin a portable translation model and authorized-only per-chip Gemma3 imports."""
from pathlib import Path
import hashlib,json,urllib.request
ROOT=Path(__file__).resolve().parents[1];ASSETS=ROOT/'app/src/main/assets'
HY_REPO='tencent/Hy-MT2-1.8B-GGUF';HY_REV='a0c709d9fac510f2c807aa3af52872340dc37a4a'
GEMMA_REPO='litert-community/Gemma3-1B-IT';GEMMA_REV='d448835d84411a08f577e25f3afebe528402d73b'
def get(repo,revision):
    url=f'https://huggingface.co/api/models/{repo}/revision/{revision}?blobs=true'
    with urllib.request.urlopen(url,timeout=60) as f:return json.load(f)
def write(path,value):path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def manifest(meta,repo,revision,filename,model,chip,runtime,gated=False):
    file=next(x for x in meta['siblings'] if x['rfilename']==filename);sha=file['lfs']['sha256'];size=file['size']
    if len(sha)!=64 or any(c not in '0123456789abcdef' for c in sha):raise RuntimeError('Publisher checksum unavailable')
    url=f'https://huggingface.co/{repo}/resolve/{revision}/{filename}'
    urls=[{'name':'Hugging Face','url':url}]
    if not gated:urls.append({'name':'HF mirror · hf-mirror.com','url':url.replace('huggingface.co','hf-mirror.com')})
    return {'id':model,'chipset':chip,'runtime':runtime,'entryFile':filename,'revision':revision,'source':f'https://huggingface.co/{repo}/tree/{revision}',
            'files':[{'name':filename,'size':size,'sha256':sha}],
            'download':{'format':'raw','size':size,'sha256':sha,'urls':urls,'requiresAuthorization':gated,
                        **({'authorizationUrl':f'https://huggingface.co/{repo}/blob/{revision}/{filename}'} if gated else {})}}
def main():
    hy=get(HY_REPO,HY_REV);gemma=get(GEMMA_REPO,GEMMA_REV)
    if hy.get('gated'):raise RuntimeError('Portable model unexpectedly requires authorization')
    catalog=json.loads((ASSETS/'device-model-catalog.json').read_text(encoding='utf8'))
    portable=next(p for p in catalog['profiles'] if p['id']=='portable')
    spec=manifest(hy,HY_REPO,HY_REV,'Hy-MT2-1.8B-Q4_K_M.gguf','hy-mt2-1.8b-cpu','any-arm64','llama-cpu')
    write(ASSETS/'hy-mt2-1.8b-cpu.json',spec)
    portable['models']=[x for x in portable['models'] if x['id']!=spec['id']]+[{'id':spec['id'],'task':'text','runtime':'llama-cpu','manifest':'hy-mt2-1.8b-cpu.json','directory':'translation-hy-mt2-1.8b-cpu','priority':80,'minAndroid':29,'androidVersion':'10','minRamBytes':3500000000,'nominalRamGb':4,'allowLowRam':False,'recommendMinRamBytes':5500000000}]
    for chip in ['SM8550','SM8650','SM8750','MT6989','MT6991']:
        key=chip.lower();filename=f'Gemma3-1B-IT_q4_ekv1280_{key}.litertlm'
        spec=manifest(gemma,GEMMA_REPO,GEMMA_REV,filename,'gemma3-1b-npu',chip,'litert-lm',True)
        name=f'gemma3-1b-npu-{key}.json';write(ASSETS/name,spec)
        profile=next((p for p in catalog['profiles'] if p['id']==key),None)
        vendor='MediaTek' if chip.startswith('MT') else 'Qualcomm'
        libs=['liblitertlm_jni.so',f'libLiteRtDispatch_{vendor}.so']
        if profile is None:
            profile={'id':key,'extends':'portable','npuChip':chip,'documentationBased':True,'requiredLibraries':libs,'models':[]};catalog['profiles'].append(profile)
        else:profile['requiredLibraries']=list(dict.fromkeys(profile['requiredLibraries']+libs))
        rule={'id':spec['id'],'task':'text','runtime':'litert-lm','manifest':name,'directory':'translation-gemma3-1b-'+key,'priority':70,'minAndroid':35,'androidVersion':'15','minRamBytes':5500000000,'nominalRamGb':6,'allowLowRam':False}
        profile['models']=[x for x in profile['models'] if x['id']!=spec['id']]+[rule]
        if chip.startswith('MT'):
            catalog['chipsets']=[c for c in catalog['chipsets'] if c['id']!=key]
            number='9300' if chip=='MT6989' else '9400'
            # MT6990/MT6993 and generic MediaTek must never receive another chip's package.
            catalog['chipsets'].insert(0,{'id':key,'name':'MediaTek Dimensity '+number,'profile':key,'match':{'soc':[chip+'(?:[A-Z][A-Z0-9._/-]*|[ _/-][A-Z0-9._/-]+)?','(?:MEDIATEK[ _-]+)?(?:DIMENSITY|D)[ _-]*'+number+r'(?:\+|[ _-]+PLUS)?']}})
    for url in ['https://developers.google.com/edge/litert/next/mediatek','https://developers.google.com/edge/litert/next/litert_lm_npu',f'https://huggingface.co/{HY_REPO}']:
        if url not in catalog['sources']:catalog['sources'].append(url)
    write(ASSETS/'device-model-catalog.json',catalog)
    evidence={'portable':{'repo':HY_REPO,'revision':HY_REV},'authorizedImports':{'repo':GEMMA_REPO,'revision':GEMMA_REV,'chipsets':['SM8550','SM8650','SM8750','MT6989','MT6991']},'physicalMediaTekTested':False,'otherVendors':'Exynos, Kirin, UNISOC and unlisted MediaTek chips retain CPU models; no unverified NPU package is substituted.'}
    write(ROOT/'docs/catalog/translation-extensions.json',evidence)
    print('Prepared portable HY-MT2 and five authorized Gemma3 chip packages')
if __name__=='__main__':main()
