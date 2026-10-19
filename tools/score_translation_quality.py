"""Score fixed text regressions; reference similarity is NOT translation accuracy."""
from pathlib import Path
import argparse, json, re, statistics
from sacrebleu.metrics import CHRF

ROOT=Path(__file__).resolve().parents[1]
def score(path):
    report=json.loads(path.read_text(encoding='utf-8-sig'))
    rules={r['id']:r for r in json.loads((ROOT/'app/src/androidTest/assets/translation-quality.json').read_text(encoding='utf8'))['samples']}
    metric=CHRF(word_order=2)
    pairs={};flags=[];latency=[]
    for row in report['translations']:
        checks=rules[row['id']]['checks'];output=row.get('output','');reasons=[]
        if not output or row.get('error'):reasons.append('runtime_error')
        if checks.get('mustMatch') and not re.search(checks['mustMatch'],output,re.I):reasons.append('critical_concept_not_found')
        for number in checks.get('digits',[]):
            if number not in output:reasons.append('number_changed')
        if checks.get('noLatinWords') and re.search('[a-zA-Z]{3,}',output):reasons.append('unexpected_latin_words')
        if row['target']=='en' and not re.search('[a-zA-Z]{3,}',output):reasons.append('missing_english_output')
        if row['category']=='reschedule' and row['target']=='en':
            if not re.search(r'\bfrom\s+Monday\s+to\s+Wednesday\b',output,re.I):reasons.append('weekday_or_direction_requires_review')
        if row['id']=='en-es-1' and not re.search(r'\bmi\b',output,re.I):reasons.append('possessive_requires_review')
        if row['id']=='en-ar-0' and 'الله' in output:reasons.append('added_religious_reference')
        if row['category']=='question' and row['target']=='en' and not re.search(r'\b(?:nearest|closest)\b',output,re.I):reasons.append('proximity_requires_review')
        row['referenceChrFpp']=round(metric.sentence_score(output,[row['reference']]).score,2)
        row['reviewFlags']=reasons
        if reasons:flags.append({'id':row['id'],'reasons':reasons,'input':row['input'],'output':output,'reference':row['reference']})
        pairs.setdefault(row['source']+'-'+row['target'],[]).append(row)
        latency.append(row['ms'])
    summary={'model':report['model'],'device':report['device'],'cases':len(latency),'runtimePassed':report.get('passed',False),
        'medianMs':round(statistics.median(latency)),'p95Ms':sorted(latency)[min(len(latency)-1,int(len(latency)*.95))],
        'maxMs':max(latency),'flaggedCases':len(flags),'flags':flags,
        'chrFpp':round(metric.corpus_score([r.get('output','') for r in report['translations']],[[r['reference'] for r in report['translations']]]).score,2),
        'scope':'94 short authored cases in 10 languages. chrF++ measures similarity to one reference, not general accuracy. Risk flags require review. Times are text inference, excluding ASR, load and streaming segmentation.',
        'pairs':{p:{'cases':len(rows),'chrFpp':round(metric.corpus_score([r.get('output','') for r in rows],[[r['reference'] for r in rows]]).score,2),'medianMs':round(statistics.median(r['ms'] for r in rows))} for p,rows in pairs.items()}}
    out=path.with_name(path.stem+'-scored.json');out.write_text(json.dumps({'summary':summary,'translations':report['translations']},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(summary,ensure_ascii=True,indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('report',type=Path);score(p.parse_args().report)
