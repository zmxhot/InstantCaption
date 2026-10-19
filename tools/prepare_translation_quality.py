"""Original regression phrases: fixed before inference, with explicit critical-concept checks."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]
PHRASES={
 'en':['Please speak slowly.','Please do not cancel my reservation.','The access code is 7890.','Where is the nearest train station?','The meeting was moved from Monday to Wednesday.'],
 'zh':['请慢一点说。','请不要取消我的预订。','访问代码是7890。','最近的火车站在哪里？','会议从星期一改到了星期三。'],
 'ja':['ゆっくり話してください。','私の予約をキャンセルしないでください。','アクセスコードは7890です。','最寄りの駅はどこですか？','会議は月曜日から水曜日に変更されました。'],
 'ko':['천천히 말씀해 주세요.','제 예약을 취소하지 말아 주세요.','접속 코드는 7890입니다.','가장 가까운 기차역은 어디인가요?','회의가 월요일에서 수요일로 변경되었습니다.'],
 'es':['Por favor, habla despacio.','Por favor, no canceles mi reserva.','El código de acceso es 7890.','¿Dónde está la estación de tren más cercana?','La reunión se cambió del lunes al miércoles.'],
 'fr':['Parlez lentement, s’il vous plaît.','Veuillez ne pas annuler ma réservation.','Le code d’accès est 7890.','Où est la gare la plus proche ?','La réunion a été déplacée de lundi à mercredi.'],
 'de':['Bitte sprechen Sie langsam.','Bitte stornieren Sie meine Reservierung nicht.','Der Zugangscode lautet 7890.','Wo ist der nächste Bahnhof?','Das Treffen wurde von Montag auf Mittwoch verschoben.'],
 'pt':['Por favor, fale devagar.','Por favor, não cancele a minha reserva.','O código de acesso é 7890.','Onde fica a estação de trem mais próxima?','A reunião foi transferida de segunda-feira para quarta-feira.'],
 'ru':['Пожалуйста, говорите медленно.','Пожалуйста, не отменяйте мою бронь.','Код доступа — 7890.','Где находится ближайший железнодорожный вокзал?','Встречу перенесли с понедельника на среду.'],
 'ar':['يرجى التحدث ببطء.','يرجى عدم إلغاء حجزي.','رمز الدخول هو 7890.','أين تقع أقرب محطة قطار؟','تم نقل الاجتماع من يوم الاثنين إلى يوم الأربعاء.']}
NEG={'en':r"\b(?:not|never|no)\b|don['’]t",'zh':'不|别|勿','ja':'ない|ません|ず','ko':'않|말|마세|취소하지','es':r'\bno\b','fr':r'\b(?:ne|pas)\b','de':r'\bnicht\b','pt':r'\bnão\b','ru':r'\bне\b','ar':'لا |عدم|ليس|لم |لن '}
QUESTION={'en':r'\bwhere\b','zh':'哪|何处','ja':'どこ|何処','ko':'어디','es':'d[oó]nde','fr':'où','de':r'\bwo\b','pt':'onde','ru':'где','ar':'أين'}
def main():
    samples=[]
    for language in PHRASES:
        if language=='en':continue
        for source,target in [('en',language),(language,'en')]:
            for i,(text,reference) in enumerate(zip(PHRASES[source],PHRASES[target])):
                checks={'mustMatch':NEG[target]} if i==1 else {'digits':['7890']} if i==2 else {'mustMatch':QUESTION[target]} if i==3 else {}
                if target in ['zh','ja','ko','ru','ar']:checks['noLatinWords']=True
                samples.append({'id':f'{source}-{target}-{i}','source':source,'target':target,'input':text,'reference':reference,'category':['slow_speech','negation','access_code','question','reschedule'][i],'checks':checks})
    for source,text,reference in [
        ('ja','明日の会議は中止ではなく、午後3時に延期されました。','明天的会议没有取消，而是推迟到了下午3点。'),
        ('ja','予約をキャンセルしないでください。','请不要取消预订。'),
        ('ko','내일 회의는 취소된 것이 아니라 오후 3시로 연기되었습니다.','明天的会议没有取消，而是推迟到了下午3点。'),
        ('ko','예약을 취소하지 말아 주세요.','请不要取消预订。')]:
        samples.append({'id':f'direct-{source}-{len(samples)}','source':source,'target':'zh','input':text,'reference':reference,'category':'direct_no_bridge','checks':{'mustMatch':'不|没|未|勿','noLatinWords':True}})
    payload={'license':'Original application regression phrases, Apache-2.0','scope':'10 languages, 94 text cases. Reference similarity and critical-concept heuristics are not a general accuracy percentage or native-speaker certification.','samples':samples}
    out=ROOT/'app/src/androidTest/assets/translation-quality.json';out.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print('Prepared',len(samples),'fixed translation cases in',len(PHRASES),'languages')
if __name__=='__main__':main()
