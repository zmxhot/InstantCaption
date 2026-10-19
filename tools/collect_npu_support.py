"""Snapshot primary-source NPU support, separating runtimes from deployable models.

This is a maintenance tool, not an app network dependency. A platform SDK support
list alone never enables a model download. Use sync_npu_catalog.py to import
supported speech recipes and hash-check their full upstream archives.
"""
from pathlib import Path
from html.parser import HTMLParser
from datetime import datetime, timezone
import concurrent.futures, hashlib, json, re, urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.runtime-cache/npu-sources'
SOURCES = {
    'litert-models': 'https://developers.google.com/edge/litert/next/litert_lm_npu',
    'mediatek-runtime': 'https://developers.google.com/edge/litert/next/mediatek',
    'samsung-runtime': 'https://developers.google.com/edge/litert/next/samsung',
    'huawei-runtime': 'https://developer.huawei.com/consumer/en/hiai/',
    'unisoc-platforms': 'https://www.unisoc.com/en/product/SmartPhone',
    'geniex-platforms': 'https://raw.githubusercontent.com/qualcomm/GenieX/main/docs/en/get-started/platforms.mdx',
    'qwen-0.6b': 'https://raw.githubusercontent.com/qualcomm/ai-hub-models/v0.64.0/src/qai_hub_models/models/qwen3_0_6b/release-assets.yaml',
    'qwen-1.7b': 'https://raw.githubusercontent.com/qualcomm/ai-hub-models/v0.64.0/src/qai_hub_models/models/qwen3_1_7b/release-assets.yaml',
    'qwen-4b': 'https://raw.githubusercontent.com/qualcomm/ai-hub-models/v0.64.0/src/qai_hub_models/models/qwen3_4b/release-assets.yaml',
    'gemma3-files': 'https://huggingface.co/api/models/litert-community/Gemma3-1B-IT/tree/main?recursive=true',
    'gemma3-metadata': 'https://huggingface.co/api/models/litert-community/Gemma3-1B-IT',
    'gemma4-files': 'https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm/tree/main?recursive=true',
    'gemma4-metadata': 'https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm',
}

class Tables(HTMLParser):
    def __init__(self):
        super().__init__();self.tables=[];self.table=None;self.row=None;self.cell=None;self.links=[];self.text=[]
    def handle_starttag(self, tag, attrs):
        if tag=='table': self.table=[]
        elif tag=='tr' and self.table is not None:self.row=[]
        elif tag in ('th','td') and self.row is not None:self.cell=[];self.links=[]
        elif tag=='a' and self.cell is not None:
            href=dict(attrs).get('href','')
            if href.startswith('https://'):self.links.append(href)
    def handle_data(self, data):
        self.text.append(data)
        if self.cell is not None:self.cell.append(data)
    def handle_endtag(self, tag):
        if tag in ('th','td') and self.cell is not None:
            self.row.append({'text':' '.join(' '.join(self.cell).split()),'links':self.links});self.cell=None
        elif tag=='tr' and self.row is not None:self.table.append(self.row);self.row=None
        elif tag=='table' and self.table is not None:self.tables.append(self.table);self.table=None

def fetch(item):
    key,url=item
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'InstantCaption-upstream-catalog'}),timeout=45) as response:
            data=response.read();resolved=response.url
        (CACHE/(key+'.source')).write_bytes(data)
        return key,{'url':url,'resolvedUrl':resolved,'sha256':hashlib.sha256(data).hexdigest(),'status':'fetched'},data.decode('utf8')
    except Exception as failure:
        return key,{'url':url,'status':'unavailable','reason':str(failure)},''

def main():
    CACHE.mkdir(parents=True,exist_ok=True)
    sources={};texts={}
    with concurrent.futures.ThreadPoolExecutor(5) as pool:
        for key,record,text in pool.map(fetch,SOURCES.items()):sources[key]=record;texts[key]=text
    models=[];runtimes=[]
    for key,vendor in [('mediatek-runtime','mediatek'),('samsung-runtime','samsung')]:
        parsed=Tables();parsed.feed(texts[key]);plain=' '.join(parsed.text)
        chips=sorted(set(re.findall(r'\b(?:MT\d{4}|E99\d{2})\b',plain)))
        runtimes.append({'vendor':vendor,'runtime':'litert','chipsets':chips,'evidence':key,'scope':'Runtime support only; not model compatibility'})
    parsed=Tables();parsed.feed(texts['litert-models'])
    for table in parsed.tables:
        if not table or len(table[0])<2 or table[0][0]['text']!='Model' or table[0][1]['text']!='SoC':continue
        for row in table[1:]:
            if len(row)<3:continue
            chip=row[1]['text'];vendor='google' if 'Tensor' in chip else 'mediatek' if chip.startswith('MT') else 'qualcomm' if chip.startswith('SM') else 'intel'
            if vendor=='intel':continue # Android ARM64 app, not desktop NPU packages.
            links=[link for cell in row for link in cell['links']]
            models.append({'label':row[0]['text'],'task':'text','vendor':vendor,'chipset':chip,'runtime':'litert-lm',
                           'source':'litert-models','modelPage':links[0] if links else '',
                           'appStatus':'package_not_integrated','downloadEnabled':False})
    # The publisher's model repository can contain newer targets than its prose
    # documentation. Preserve exact target names, immutable revision and gating.
    for size,repo in [('3','litert-community/Gemma3-1B-IT'),('4','litert-community/gemma-4-E2B-it-litert-lm')]:
        metadata=json.loads(texts['gemma'+size+'-metadata'] or '{}')
        for item in json.loads(texts['gemma'+size+'-files'] or '[]'):
            filename=item.get('path','');target=re.search(r'(sm\d{4}|mt\d{4}|Google_Tensor_G\d+)\.litertlm$',filename,re.I)
            if not target:continue
            chip=target[1].upper() if target[1][:2].lower() in ('sm','mt') else target[1].removeprefix('Google_')
            vendor='qualcomm' if chip.startswith('SM') else 'mediatek' if chip.startswith('MT') else 'google'
            sha=item.get('lfs',{}).get('oid','')
            models.append({'label':'Gemma3-1B' if size=='3' else 'Gemma4-E2B','task':'text','vendor':vendor,'chipset':chip,
                'runtime':'litert-lm','source':'gemma'+size+'-files','revision':metadata.get('sha',''),
                'modelPage':'https://huggingface.co/'+repo+'/blob/'+metadata.get('sha','main')+'/'+filename,
                'downloadUrl':'https://huggingface.co/'+repo+'/resolve/'+metadata.get('sha','main')+'/'+filename,
                'size':item.get('size',0),'sha256':sha if re.fullmatch('[a-f0-9]{64}',sha) else None,
                'requiresPublisherAuthorization':bool(metadata.get('gated')),'appStatus':'publisher_authorization_required' if metadata.get('gated') else 'package_not_integrated',
                'downloadEnabled':False})
    for size in ('0.6b','1.7b','4b'):
        key='qwen-'+size
        for chip in re.findall(r'^      (qualcomm-[^:]+):',texts[key],re.M):
            if not 'snapdragon-8-' in chip:continue
            models.append({'label':'Qwen3-'+size.upper(),'task':'text','vendor':'qualcomm','chipset':chip,
                'runtime':'qairt','source':key,'appStatus':'integrated' if 'elite' in chip else 'runtime_not_integrated',
                'downloadEnabled':'elite' in chip})
    catalog=json.loads((ROOT/'app/src/main/assets/device-model-catalog.json').read_text(encoding='utf8'))
    # Actual selectable state comes from the app catalog, never a broad SDK list.
    integrated=[]
    for profile in catalog['profiles']:
        for model in profile['models']:
            if model['runtime'] in ('cpu','llama-cpu'):continue
            integrated.append({'modelId':model['id'],'task':model['task'],'chipset':profile['npuChip'],
                               'manifest':model['manifest'],'runtime':model['runtime']})
    # Only concrete app catalog entries may upgrade publisher records to enabled.
    normalize=lambda value: re.sub('[^a-z0-9]','',value.lower()).removeprefix('google')
    for model in models:
        if model['runtime']!='litert-lm':continue
        name=normalize(model['label'])
        mid='gemma4-e2b-npu' if name.startswith('gemma4') else 'gemma3-1b-npu'
        enabled=any(row['modelId']==mid and normalize(row['chipset'])==normalize(model['chipset']) for row in integrated)
        if enabled:
            gated=mid=='gemma3-1b-npu'
            model['appStatus']='integrated_authorized_import' if gated else 'integrated'
            model['downloadEnabled']=not gated
            model['authorizedImportEnabled']=gated
            model['requiresPublisherAuthorization']=gated
        elif mid=='gemma3-1b-npu':
            model['appStatus']='publisher_authorization_required';model['downloadEnabled']=False
    out={'schemaVersion':1,'retrievedAt':datetime.now(timezone.utc).isoformat(),'sources':sources,
        'runtimeSupport':runtimes,'upstreamModels':models,'appDownloads':integrated,
        'unresolvedVendors':[{'vendor':vendor,'source':key,'appStatus':'no_ready_caption_package_found_in_checked_sources'}
                             for vendor,key in [('samsung','samsung-runtime'),('huawei','huawei-runtime'),('unisoc','unisoc-platforms')]],
        'policy':'Only appDownloads are selectable. Hardware/runtime support alone is not a downloadable model. No physical handset whitelist.'}
    for path in [ROOT/'docs/catalog/npu-support.json',ROOT/'app/src/main/assets/npu-upstream-support.json']:
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'sources':len(sources),'sourceErrors':[k for k,v in sources.items() if v['status']!='fetched'],
                      'upstreamModelRecords':len(models),'enabledModelChipPairs':len(integrated)},indent=2))

if __name__=='__main__':main()
