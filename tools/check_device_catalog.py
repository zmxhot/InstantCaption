"""Validate bundled device rules and referenced model packages without testing phones."""
import json, re
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'

def check():
    data=json.loads((ASSETS/'device-model-catalog.json').read_text(encoding='utf8'))
    assert data['schemaVersion']==1 and data['minimumAndroid']>=29 and data['abi']=='arm64-v8a'
    speech={'x-asr','nemotron','sensevoice','zipformer','whisper-tiny','whisper-base','sensevoice-cpu'}
    text={'opus','qwen3-0.6b-npu','qwen3-1.7b-npu','qwen3-4b-npu','gemma4-e2b-npu','gemma3-1b-npu','hy-mt2-1.8b-cpu'}
    profiles={}
    references=0
    for profile in data['profiles']:
        parent=profile.get('extends');models=dict(profiles[parent]['models']) if parent else {}
        assert profile['id'] not in profiles
        seen=set()
        for model in profile['models']:
            mid=model['id'];assert mid not in seen;seen.add(mid)
            assert mid in (speech if model['task']=='speech' else text)
            assert model['runtime'] in ('cpu','llama-cpu','qnn','qairt','litert-lm')
            assert model.get('minAndroid',data['minimumAndroid'])>=data['minimumAndroid']
            assert model.get('minRamBytes',0)>=0 and model.get('recommendMinRamBytes',0)>=0
            assert re.fullmatch(r'\d+(?:\.\d+)?',model['androidVersion'])
            assert re.fullmatch(r'[a-z0-9][a-z0-9._-]*\.json',model['manifest'])
            assert re.fullmatch(r'[a-z0-9][a-z0-9._-]*',model['directory'].replace('{target}','zh'))
            manifest=json.loads((ASSETS/model['manifest']).read_text(encoding='utf8'))
            if model['task']=='speech':
                spec=manifest[mid];assert model['directory']==spec['directory']
                assert model['runtime']==('cpu' if mid in ('whisper-tiny','whisper-base','sensevoice-cpu') else 'qnn')
            elif mid=='opus':
                assert model['runtime']=='cpu' and '{target}' in model['directory']
                assert set(manifest)=={'zh','de','fr','es','ru','ar','hi','it','pt'}
                models[mid]=model;references+=1;continue
            elif mid=='hy-mt2-1.8b-cpu':
                spec=manifest;assert spec['id']==mid and spec['chipset']=='any-arm64' and model['runtime']=='llama-cpu'
            else:
                spec=manifest;assert spec['id']==mid and spec['chipset']==profile['npuChip'] and model['runtime']==('litert-lm' if mid.startswith('gemma') else 'qairt')
            if model['runtime'] not in ('cpu','llama-cpu'):
                assert profile.get('npuChip') and profile.get('requiredLibraries')
                if model['task']=='speech' and mid!='nemotron':
                    assert profile['npuChip'] in spec['download']['urls'][0]['url']
            if spec['download'].get('format')=='raw':
                assert len(spec['files'])==1 and spec['entryFile']==spec['files'][0]['name']
                assert spec['download']['size']==spec['files'][0]['size'] and spec['download']['sha256']==spec['files'][0]['sha256']
            files=spec['files'];assert len({f['name'] for f in files})==len(files)
            for file in files:
                assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*',file['name'])
                assert re.fullmatch(r'[0-9a-f]{64}',file['sha256']) and file['size']>0
            assert re.fullmatch(r'[0-9a-f]{64}',spec['download']['sha256'])
            assert all(source['url'].startswith('https://') for source in spec['download']['urls'])
            if spec['download'].get('requiresAuthorization'):
                assert mid=='gemma3-1b-npu' and len(spec['download']['urls'])==1
                assert spec['download']['authorizationUrl'].startswith('https://huggingface.co/litert-community/Gemma3-1B-IT/blob/')
            models[mid]=model;references+=1
        profiles[profile['id']]={'models':models,'npu':profile.get('npuChip','')}
    assert profiles[data['defaultProfile']]['npu']==''
    assert set(profiles[data['legacyStorageProfile']]['models'])==speech|text
    ids=set()
    for chip in data['chipsets']:
        assert chip['id'] not in ids;ids.add(chip['id'])
        profile=profiles[chip['profile']];match=chip['match']
        assert set(match)<= {'soc','socManufacturer','hardware'} and any(match.values())
        if profile['npu']:assert not match.get('socManufacturer') and not match.get('hardware')
        for patterns in match.values():
            for pattern in patterns:assert len(pattern)<=256;re.compile(pattern,re.IGNORECASE)
    return {'passed':True,'chipsetRules':len(ids),'modelProfiles':len(profiles),'packageReferences':references,'physicalDeviceTestsRequired':False}

if __name__=='__main__':print(json.dumps(check(),indent=2))
