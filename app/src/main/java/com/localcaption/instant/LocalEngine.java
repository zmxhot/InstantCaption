package com.localcaption.instant;

import android.content.Context;
import android.os.SystemClock;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Text translation is in-process; speech can reuse Android's on-device service. */
public final class LocalEngine {
    @android.annotation.SuppressLint("StaticFieldLeak") // Singleton stores only the application context.
    private static LocalEngine instance;
    public static synchronized LocalEngine get(Context c) {
        if (instance == null) instance = new LocalEngine(c.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final ExecutorService setup = Executors.newSingleThreadExecutor();
    private final ExecutorService translationWorker = Executors.newSingleThreadExecutor();
    private final TranslationBacklog pending = new TranslationBacklog();
    private volatile TranslationRouter translator;
    private final DeviceProfile device;
    private volatile String sourceLanguage,targetLanguage;
    private int activeSessions;
    private volatile byte[] vadModel;
    private volatile NpuRecognizer npu;
    private volatile boolean systemSpeech,systemFailed;
    private volatile SystemSpeech.Support systemSupport=SystemSpeech.Support.unavailable("en","checking");
    private volatile String speechChoice="auto";
    private volatile String translationChoice="opus";
    private boolean pumping;
    private long epoch;
    private volatile NpuModel selected;
    enum FailureStage { TEXT, SPEECH, GENERAL }
    private volatile FailureStage failedStage;
    private volatile ModelFailure.Kind failureKind;
    private final java.util.Set<String> failedText=new java.util.HashSet<>(),failedSpeech=new java.util.HashSet<>();
    private volatile ModelRecovery.Plan recovery;
    private LocalEngine(Context context){
        this.context=context;device=DeviceProfile.detect(context);
        android.content.SharedPreferences prefs=context.getSharedPreferences("engine",0);
        String[] pair=SpeechLanguage.configuredPair(prefs.getString("speechSource",null),prefs.getString("speechTarget",null),SpeechLanguage.systemLanguageTag(context));
        sourceLanguage=pair[0];targetLanguage=pair[1];
        speechChoice=readSpeechChoice();
        String recommendation=ModelCompatibility.recommended(device,sourceLanguage,targetLanguage);
        String savedTranslation=prefs.getString("translationModel",recommendation==null?"opus":recommendation);
        String compatibleTranslation=ModelCompatibility.forSelection(device,sourceLanguage,targetLanguage,savedTranslation);
        translationChoice=compatibleTranslation==null?"opus":compatibleTranslation;
        if(!automaticSpeech()&&!"system".equals(speechChoice)&&!isModelCompatible(NpuModel.fromId(speechChoice)))speechChoice="auto";
        prefs.edit().putString("translationModel",translationChoice).putString("speechChoice:"+sourceLanguage+":"+targetLanguage,speechChoice).apply();
        prefs.edit().putString("speechSource",sourceLanguage).putString("speechTarget",targetLanguage).apply();
        translator=new TranslationRouter(new AndroidTranslationFactory(context,sourceLanguage,targetLanguage,translationChoice));
        selected=NpuModel.SENSEVOICE_CPU;
        ensureCompatibleSelection();
    }
    public String sourceLanguage(){return sourceLanguage;}
    public String targetLanguage(){return targetLanguage;}
    String speechChoice(){return speechChoice;}
    String translationChoice(){return translationChoice;}
    boolean usesQwenTranslation(){return QwenModel.known(translationChoice);}
    boolean automaticSpeech(){return "auto".equals(speechChoice);}
    private String readSpeechChoice(){
        String choice=context.getSharedPreferences("engine",0).getString("speechChoice:"+sourceLanguage+":"+targetLanguage,"auto");
        if("auto".equals(choice)||"system".equals(choice))return choice;
        for(NpuModel m:NpuModel.values())if(m.id.equals(choice))return choice;
        return "auto";
    }
    String speechChoiceLabel(){return automaticSpeech()?Ui.t(context,"自动选择","Automatic"):"system".equals(speechChoice)?Ui.t(context,"系统离线识别","System offline recognition"):NpuModel.fromId(speechChoice).label(context);}
    Context applicationContext(){return context;}
    boolean usesSystemSpeech(){return systemSpeech;}
    String systemSpeechLanguage(){return systemSupport.language;}
    String systemSpeechState(){return systemSupport.reason;}
    boolean canDownloadSystemSpeech(){return !recognitionUsesEnglishBridge()&&!systemSupport.ready&&SystemSpeech.matchLanguage(sourceLanguage,systemSupport.downloadable)!=null;}
    NpuModel requiredSpeechModel(){return systemSpeech?null:selected;}
    String recognitionBackend(){return systemSpeech?"system-offline":selected.id;}
    boolean hasLocalSpeechRuntime(){return npu!=null;}
    public boolean recognitionUsesEnglishBridge(){
        return !usesQwenTranslation()&&!SpeechLanguage.canonical(sourceLanguage).equals(targetLanguage)&&!sourceLanguage.equals("en");
    }
    boolean translationAvailable(){return ModelCompatibility.supports(device,sourceLanguage,targetLanguage,translationChoice);}
    public boolean isModelCompatible(NpuModel model){return device.supports(model)&&model.supportsLanguage(sourceLanguage)&&(!recognitionUsesEnglishBridge()||model.supportsEnglishTranslation());}
    public synchronized boolean configurationBusy(){return CaptionState.S.running||CaptionState.S.preparing||ModelStore.active||!translationsIdle()||activeSessions>0;}
    synchronized void sessionOpened(){activeSessions++;}
    synchronized void sessionClosed(){activeSessions=Math.max(0,activeSessions-1);CaptionState.MAIN.post(()->CaptionState.S.changed());}
    public void setSourceLanguage(String language){setLanguages(language,targetLanguage);}
    public void setTargetLanguage(String language){setLanguages(sourceLanguage,language);}
    public synchronized void setLanguages(String source,String target){
        if(configurationBusy()||!SpeechLanguage.supported(source)||!SpeechLanguage.supported(target))return;
        source=SpeechLanguage.fromId(source).id;target=SpeechLanguage.canonical(target);
        if(source.equals(sourceLanguage)&&target.equals(targetLanguage))return;
        String compatible=ModelCompatibility.forSelection(device,source,target,translationChoice);
        if(compatible==null){CaptionState.S.status(AppPrefs.localized(context).getString(R.string.route_unavailable));return;}
        translationChoice=compatible;
        context.getSharedPreferences("engine",0).edit().putString("modelFor:"+sourceLanguage+":"+targetLanguage,selected.id).apply();
        sourceLanguage=source;targetLanguage=target;speechChoice=readSpeechChoice();systemSpeech=false;systemFailed=false;systemSupport=SystemSpeech.Support.unavailable(source,"checking");cancelPending();CaptionState.S.ready=false;
        if(!automaticSpeech()&&!"system".equals(speechChoice)&&!isModelCompatible(NpuModel.fromId(speechChoice))){speechChoice="auto";context.getSharedPreferences("engine",0).edit().putString("speechChoice:"+source+":"+target,"auto").apply();}
        translator.close();if(usesQwenTranslation()&&npu!=null){npu.close();npu=null;}translator=new TranslationRouter(new AndroidTranslationFactory(context,source,target,translationChoice));
        context.getSharedPreferences("engine",0).edit().putString("speechSource",source).putString("speechTarget",target).putString("translationModel",translationChoice).apply();
        selected=NpuModel.fromId(context.getSharedPreferences("engine",0).getString("modelFor:"+source+":"+target,selected.id));
        ensureCompatibleSelection();context.getSharedPreferences("engine",0).edit().putString("npuModel",selected.id).apply();CaptionState.S.changed();prepare();
    }
    private void ensureCompatibleSelection(){
        if(!translationAvailable())return;
        if(!automaticSpeech()&&!"system".equals(speechChoice)){selected=NpuModel.fromId(speechChoice);return;}
        NpuModel sense=device.supports(NpuModel.SENSEVOICE)?NpuModel.SENSEVOICE:NpuModel.SENSEVOICE_CPU;
        if(!recognitionUsesEnglishBridge()&&sense.supportsLanguage(sourceLanguage)){
            // Reuse an installed portable SenseVoice instead of downloading an NPU copy.
            if(!ModelStore.installed(context,sense)&&ModelStore.installed(context,NpuModel.SENSEVOICE_CPU))sense=NpuModel.SENSEVOICE_CPU;
            selected=sense;context.getSharedPreferences("engine",0).edit().putString("npuModel",selected.id).apply();return;
        }
        NpuModel fallback=null;
        java.util.List<NpuModel> candidates=new java.util.ArrayList<>(device.downloadableModels(sourceLanguage));
        if(recognitionUsesEnglishBridge()){
            NpuModel preferred=device.recommendedEnglishBridge(sourceLanguage);
            if(preferred!=null){candidates.remove(preferred);candidates.add(0,preferred);}
        }
        for(NpuModel candidate:candidates){
            if(recognitionUsesEnglishBridge()&&!candidate.supportsEnglishTranslation())continue;
            if(fallback==null)fallback=candidate;
            if(ModelStore.installed(context,candidate)){fallback=candidate;break;}
        }
        if(fallback!=null){selected=fallback;context.getSharedPreferences("engine",0).edit().putString("npuModel",selected.id).apply();}
    }
    public boolean usesNpu(){return !systemSpeech&&selected.isNpu;}
    public NpuModel selectedModel(){return selected;}
    public String engineName(){return systemSpeech?Ui.t(context,"系统离线语音识别","System offline speech recognition"):selected.label(context)+(selected.isNpu?" (QNN HTP NPU)":" (CPU)");}
    public synchronized void selectModel(NpuModel model){
        if(model!=null)selectSpeechChoice(model.id);
    }
    synchronized boolean selectTranslationChoice(String choice){return selectTranslationChoice(choice,targetLanguage);}
    synchronized boolean selectTranslationChoice(String choice,String target){
        if(configurationBusy()||!ModelCompatibility.supports(device,sourceLanguage,target,choice))return false;
        if(QwenModel.known(choice)&&!QwenModel.fromId(choice).isSupported(context))return false;
        target=SpeechLanguage.canonical(target);
        if(choice.equals(translationChoice)&&target.equals(targetLanguage)&&CaptionState.S.ready)return true;
        translationChoice=choice;
        context.getSharedPreferences("engine",0).edit().putString("translationModel",choice).apply();
        if(!target.equals(targetLanguage)){setLanguages(sourceLanguage,target);return true;}
        cancelPending();CaptionState.S.ready=false;translator.close();
        // Release the speech NPU context before loading a replacement LLM to reduce peak memory.
        if(npu!=null){npu.close();npu=null;}
        translator=new TranslationRouter(new AndroidTranslationFactory(context,sourceLanguage,targetLanguage,translationChoice));
        systemSpeech=false;systemFailed=false;ensureCompatibleSelection();CaptionState.S.changed();prepare();return true;
    }
    synchronized boolean selectSpeechChoice(String choice){
        if(configurationBusy())return false;
        if(!"auto".equals(choice)&&!"system".equals(choice)){
            NpuModel model=null;for(NpuModel m:NpuModel.values())if(m.id.equals(choice))model=m;
            if(model==null||!isModelCompatible(model))return false;
        }
        if("system".equals(choice)&&recognitionUsesEnglishBridge())return false;
        if(choice.equals(speechChoice)&&CaptionState.S.ready)return true;
        speechChoice=choice;systemSpeech=false;systemFailed=false;
        context.getSharedPreferences("engine",0).edit().putString("speechChoice:"+sourceLanguage+":"+targetLanguage,choice).apply();
        ensureCompatibleSelection();
        context.getSharedPreferences("engine",0).edit().putString("npuModel",selected.id).apply();
        CaptionState.S.ready=false;CaptionState.S.asrMs=0;CaptionState.S.skippedSegments=0;cancelPending();prepare();
        return true;
    }
    NpuRecognizer npuRecognizer(){if(npu==null)throw new IllegalStateException("NPU 模型尚未加载");return npu;}
    public String recognizeNpuBlocking(short[] pcm){return npuRecognizer().recognize(pcm);}
    public synchronized void prepare() {
        if (CaptionState.S.running || CaptionState.S.preparing || activeSessions>0 || !translationsIdle() || CaptionState.S.ready) return;
        if(ModelStore.active||DownloadJournal.running(context))return;
        CaptionState.S.preparing = true; CaptionState.S.ready = false; cancelPending();
        CaptionState.S.status("正在准备本地语音模型…");
        setup.execute(() -> {
            FailureStage stage=FailureStage.GENERAL;
            try {
                if(!translationAvailable())throw new IllegalStateException("当前语言组合暂无本地 AI 翻译模型，请更换来源或目标语言");
                RuntimeHealth.beforeLoad(context,null);
                for(NpuModel m:NpuModel.values())ModelStore.recoverPrevious(context,m.id);
                for(QwenModel m:QwenModel.values())ModelStore.recoverPrevious(context,m.id);
                for(String language:TranslationModel.targets())ModelStore.recoverPrevious(context,TranslationModel.id(language));
                if (vadModel == null) {
                    try (InputStream in=context.getAssets().open("silero-vad-v6.2.onnx"); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                        byte[] buffer=new byte[65536]; int n;
                        while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                        vadModel=out.toByteArray();
                    }
                    try (SpeechDetector detector=new SpeechDetector(context,vadModel)) { detector.probability(new short[SpeechDetector.FRAME]); }
                }
                Exception translationFailure=null;
                if(!translator.ready()){
                    stage=FailureStage.TEXT;
                    CaptionState.S.status("正在检查本地翻译方式…");
                    try{translator.prepare();}catch(Exception e){translationFailure=e;}
                }
                stage=FailureStage.SPEECH;
                if((automaticSpeech()||"system".equals(speechChoice))&&!recognitionUsesEnglishBridge()&&!systemFailed){
                    CaptionState.S.status("正在检查系统离线语音识别…");
                    try{systemSupport=SystemSpeech.probe(context,sourceLanguage);}catch(Exception error){systemSupport=SystemSpeech.Support.unavailable(sourceLanguage,"check-failed");}
                    systemSpeech=systemSupport.ready;
                }else{systemSpeech=false;if(recognitionUsesEnglishBridge())systemSupport=SystemSpeech.Support.unavailable(sourceLanguage,"english-bridge");}
                ensureCompatibleSelection();
                if("system".equals(speechChoice)&&!systemSpeech)throw new IllegalStateException("所选系统离线识别不可用，请准备系统语音包或选择其他识别模型");
                if(translationFailure!=null){stage=FailureStage.TEXT;throw translationFailure;}
                if(systemSpeech){
                    if(npu!=null){npu.close();npu=null;}
                    CaptionState.MAIN.post(()->{clearFailure();CaptionState.S.ready=true;CaptionState.S.preparing=false;CaptionState.S.status="离线模型就绪 · "+Ui.translationBackend(context,translationBackend());CaptionState.S.changed();});
                    return;
                }
                if(!device.supports(selected))throw new IllegalStateException(device.reason(context,selected));
                if(!isModelCompatible(selected))throw new IllegalStateException("当前设备暂无支持此语言组合的语音模型，请更换语言");
                if(!ModelStore.installed(context,selected))throw new ModelFailure(ModelFailure.Kind.MISSING);
                if(npu!=null&&(npu.model!=selected||!npu.sourceLanguage.equals(sourceLanguage)||npu.englishBridge!=recognitionUsesEnglishBridge())){npu.close();npu=null;}
                if(npu==null){CaptionState.S.status("正在加载 "+(selected.isNpu?"NPU":"CPU")+" · "+selected.label+(selected==NpuModel.NEMOTRON?"（首次编译可能需要数分钟）":"…"));npu=new NpuRecognizer(context,selected,sourceLanguage,recognitionUsesEnglishBridge());}
                RuntimeHealth.loaded(context,selected.id);
                CaptionState.MAIN.post(() -> {clearFailure();CaptionState.S.ready=true; CaptionState.S.preparing=false; CaptionState.S.status="离线模型就绪 · "+Ui.translationBackend(context,translationBackend()); CaptionState.S.changed(); });
            } catch (Exception | LinkageError | OutOfMemoryError e) {
                FailureStage failed=stage;CaptionState.MAIN.post(() -> modelFailed(failed,e));
            }
        });
    }
    synchronized void modelFailed(FailureStage stage,Throwable error){
        failedStage=stage;failureKind=ModelFailure.classify(error);
        if(stage==FailureStage.TEXT)failedText.add(translationChoice);
        if(stage==FailureStage.SPEECH)failedSpeech.add(systemSpeech?"system":selected.id);
        recovery=ModelRecovery.find(context,device,sourceLanguage,targetLanguage,translationChoice,speechChoice,
            stage!=FailureStage.SPEECH,failedText,failedSpeech,systemSupport.ready&&!systemFailed);
        CaptionState.S.ready=false;CaptionState.S.preparing=false;cancelPending();
        context.stopService(new android.content.Intent(context,CaptionService.class));
        CaptionState.S.status(recoveryMessage());
    }
    private synchronized void clearFailure(){failedStage=null;failureKind=null;recovery=null;}
    boolean hasModelFailure(){return failedStage!=null;}
    String recoveryMessage(){return failureKind==null?"":AppPrefs.localized(context).getString(ModelFailure.resource(failureKind));}
    ModelRecovery.Plan recoveryPlan(){return recovery;}
    synchronized boolean recoverInstalledModels(){
        if(configurationBusy()||recovery==null||RuntimeHealth.thermal(context)>=android.os.PowerManager.THERMAL_STATUS_CRITICAL)return false;
        ModelRecovery.Plan plan=ModelRecovery.find(context,device,sourceLanguage,targetLanguage,translationChoice,speechChoice,
            failedStage!=FailureStage.SPEECH,failedText,failedSpeech,systemSupport.ready&&!systemFailed);
        if(plan==null){recovery=null;CaptionState.S.changed();return false;}
        // Set both choices atomically before one preparation; avoid loading an incompatible intermediate route.
        speechChoice=plan.speech();context.getSharedPreferences("engine",0).edit()
            .putString("speechChoice:"+sourceLanguage+":"+targetLanguage,speechChoice).apply();
        return selectTranslationChoice(plan.text());
    }
    synchronized void retryModels(){
        if(configurationBusy())return;
        translator.close();if(npu!=null){npu.close();npu=null;}
        CaptionState.S.ready=false;systemFailed=false;prepare();
    }
    public synchronized void recheckSpeech(){
        if(configurationBusy())return;
        systemFailed=false;CaptionState.S.ready=false;prepare();
    }
    synchronized void useRecommendedConfiguration(){
        if(configurationBusy())return;
        String choice=ModelCompatibility.recommended(device,sourceLanguage,targetLanguage);if(choice==null)return;
        speechChoice="auto";context.getSharedPreferences("engine",0).edit().putString("speechChoice:"+sourceLanguage+":"+targetLanguage,"auto").apply();
        CaptionState.S.ready=false;selectTranslationChoice(choice);
    }
    public synchronized void downloadSystemSpeech(){
        if(configurationBusy()||!canDownloadSystemSpeech())return;
        boolean wasReady=CaptionState.S.ready;CaptionState.S.ready=false;CaptionState.S.preparing=true;CaptionState.S.status("正在准备系统离线语音包…");
        setup.execute(()->{
            String error=null;
            try{SystemSpeech.download(context,systemSupport);}catch(Exception e){error=message(e);}
            final String problem=error;
            CaptionState.MAIN.post(()->{CaptionState.S.preparing=false;if(problem!=null){CaptionState.S.ready=wasReady;CaptionState.S.status("系统语音包："+problem);}else recheckSpeech();});
        });
    }
    void systemSpeechFailed(String reason)throws Exception{
        if(!automaticSpeech()){CaptionState.S.ready=false;throw new IllegalStateException("所选系统离线识别暂不可用，请选择其他识别模型");}
        systemSpeech=false;systemFailed=true;systemSupport=SystemSpeech.Support.unavailable(sourceLanguage,"runtime-failed");ensureCompatibleSelection();
        CaptionState.S.status("系统识别暂不可用，正在使用本地语音模型…");
        if(!ModelStore.installed(context,selected)){CaptionState.S.ready=false;throw new IllegalStateException("请到模型管理准备 "+selected.label);}
        try{if(npu==null)npu=new NpuRecognizer(context,selected,sourceLanguage,recognitionUsesEnglishBridge());}
        catch(Exception error){CaptionState.S.ready=false;throw error;}
        CaptionState.MAIN.post(()->CaptionState.S.changed());
    }
    synchronized boolean unloadForRemoval(NpuModel model){
        if(CaptionState.S.running||CaptionState.S.preparing||activeSessions>0)return false;
        if(npu!=null&&npu.model==model){npu.close();npu=null;}
        if(!systemSpeech&&selected==model){CaptionState.S.ready=false;cancelPending();CaptionState.S.status("本地模型已删除，请在设置中下载安装");}
        return true;
    }
    boolean translationReady(){return translator.ready();}
    String translationBackend(){return translator.backend();}
    synchronized boolean unloadTranslation(){
        return unloadTranslation(targetLanguage);
    }
    synchronized boolean unloadTranslation(String target){
        if(CaptionState.S.running||CaptionState.S.preparing||activeSessions>0||!translationsIdle())return false;
        if(!targetLanguage.equals(target))return true;
        if("opus".equals(translator.backend())){
            cancelPending();CaptionState.S.ready=false;translator.unloadOpus();
            CaptionState.S.status("本地文字翻译模型已删除，请到模型管理下载安装");
        }
        return true;
    }
    synchronized boolean unloadQwenTranslation(){return unloadQwenTranslation(QwenModel.ID);}
    synchronized boolean unloadQwenTranslation(String modelId){
        if(CaptionState.S.running||CaptionState.S.preparing||activeSessions>0||!translationsIdle())return false;
        if(modelId.equals(translator.backend())){cancelPending();CaptionState.S.ready=false;translator.close();CaptionState.S.status(context.getString(R.string.ui_model_removed_download_a_model_in_settings_e5010b44));}
        return true;
    }
    public SpeechDetector speechDetector() throws Exception {
        if(vadModel==null) throw new IllegalStateException("人声检测模型尚未加载");
        return new SpeechDetector(context,vadModel);
    }
    public synchronized void cancelPending() { epoch++; pending.clear(); }
    synchronized long generation() { return epoch; }
    synchronized boolean isCurrent(long generation) { return epoch==generation; }
    public synchronized void translate(long id, String en, int revision) {
        if(!pending.offer(id,en,revision,epoch,SystemClock.elapsedRealtime())){overloaded(id,revision);return;}
        if (!pumping) { pumping=true; translationWorker.execute(this::pump); }
    }
    private void pump() {
        while (true) {
            TranslationBacklog.Request r;
            synchronized (this) {
                if (pending.isEmpty()) { pumping=false;CaptionState.MAIN.post(()->CaptionState.S.changed());return; }
                r=pending.poll();
            }
            try {
                if(SystemClock.elapsedRealtime()-r.queuedAt>TranslationBacklog.MAX_WAIT_MS){overloaded(r.id,r.revision);continue;}
                long t=SystemClock.elapsedRealtime();
                String zh=translator.translate(r.text,()->requestCurrent(r));
                synchronized (this) { if (r.epoch != epoch) continue; }
                CaptionState.S.chinese(r.id,zh,r.revision,(int)(SystemClock.elapsedRealtime()-t));
            } catch (java.util.concurrent.CancellationException ignored) {
            } catch (Exception | LinkageError | OutOfMemoryError e) { if(isCurrent(r.epoch)){
                CaptionState.S.translationProblem(r.id,r.revision,AppPrefs.localized(context).getString(R.string.translation_failed));
                CaptionState.MAIN.post(()->{if(isCurrent(r.epoch))modelFailed(FailureStage.TEXT,e);});
            } }
        }
    }
    private synchronized boolean requestCurrent(TranslationBacklog.Request r){return r.epoch==epoch&&!pending.superseded(r);}
    private void overloaded(long id,int revision){
        String message=AppPrefs.localized(context).getString(R.string.translation_overload);
        CaptionState.S.translationProblem(id,revision,message);
        CaptionState.MAIN.post(()->{CaptionState.S.status=message;context.stopService(new android.content.Intent(context,CaptionService.class));CaptionState.S.changed();});
    }
    synchronized int pendingTranslations(){return pending.size();}
    public String translateBlocking(String en) throws Exception { return translator.translate(en,()->true); }
    public synchronized boolean translationsIdle(){return !pumping&&pending.isEmpty();}
    private static String message(Throwable t) { while (t.getCause()!=null) t=t.getCause(); return t.getMessage()==null?t.toString():t.getMessage(); }
}
