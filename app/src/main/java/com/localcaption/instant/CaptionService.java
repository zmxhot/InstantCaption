package com.localcaption.instant;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;

public class CaptionService extends Service implements CaptionState.Observer {
    public static final String START="start", STOP="stop";
    private volatile boolean capturing;
    private volatile AudioRecord recorder;
    private MediaProjection projection;
    private PowerManager.WakeLock wake;
    private FloatingCaptionOverlay overlay;
    private Thread audioThread;
    private static final long OVERLAY_IDLE_MS=60_000;
    private long lastSpeechAt=SystemClock.elapsedRealtime(),overlayClearedThrough=-1;
    private boolean idleCheckScheduled;
    private final Runnable idleCheck=()->{idleCheckScheduled=false;changed();};
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("captions",Ui.t(this,"实时离线字幕","Live offline captions"),NotificationManager.IMPORTANCE_LOW));
        overlay=new FloatingCaptionOverlay(this);
        CaptionState.S.observe(this);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent==null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (capturing) return START_NOT_STICKY;
        boolean playback=intent.getBooleanExtra("playback",false);
        try {
            Intent open=new Intent(this,MainActivity.class);
            PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptionService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
            Notification n=new Notification.Builder(this,"captions").setSmallIcon(R.drawable.ic_app)
                .setContentTitle(Ui.t(this,"瞬译 · 正在显示离线字幕","Instant Caption · offline captions")).setContentText(playback?Ui.t(this,"手机声音 → ","Device audio → ")+SpeechLanguage.label(this,LocalEngine.get(this).targetLanguage()):Ui.t(this,"麦克风 → ","Microphone → ")+SpeechLanguage.label(this,LocalEngine.get(this).targetLanguage()))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pi).setOngoing(true).addAction(new Notification.Action.Builder(null,Ui.t(this,"停止","Stop"),stop).build()).build();
            int serviceType=playback?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:(Build.VERSION.SDK_INT>=30?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:0);
            startForeground(1,n,serviceType);
            if (!CaptionState.S.ready) throw new IllegalStateException("请先准备离线模型");
            if (playback) {
                Intent data=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("projectionData",Intent.class):intent.getParcelableExtra("projectionData");
                if (data==null) throw new IllegalArgumentException("尚未获得手机声音授权");
                projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,data);
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { if (capturing) { CaptionState.S.status("手机声音授权已结束，请重新开始"); stopSelf(); } }
                },CaptionState.MAIN);
            }
            AudioFormat format=new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord.Builder builder=new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(min*4,32000));
            if (playback) builder.setAudioPlaybackCaptureConfig(new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build());
            else builder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw new SecurityException("需要麦克风权限");
            recorder=builder.build();
            if (recorder.getState()!=AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("无法打开音频输入");
            wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"instant:captions");
            wake.acquire(4*60*60*1000L);
            lastSpeechAt=SystemClock.elapsedRealtime();overlayClearedThrough=-1;
            capturing=true; CaptionState.S.running=true; CaptionState.S.source=playback?"手机声音":"麦克风";
            CaptionState.S.status="正在聆听 · 仅翻译人声"; CaptionState.S.changed();
            LocalEngine.get(this).cancelPending();
            CaptionState.S.skippedSegments=0;CaptionState.S.asrMs=0;
            int interval=intent.getIntExtra("interval",450);
            AudioRecord input=recorder;
            audioThread=new Thread(() -> capture(input,interval,startId),"live-audio"); audioThread.start();
        } catch (Exception e) { CaptionState.S.status("启动失败："+e.getMessage()); stopSelf(); }
        return START_NOT_STICKY;
    }
    private void capture(AudioRecord input,int interval,int startId) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        try (CaptionPipeline pipeline=new CaptionPipeline(LocalEngine.get(this),interval,()->{
            long now=SystemClock.elapsedRealtime();
            CaptionState.MAIN.post(()->{if(capturing)heardSpeech(now);});
        })) {
            short[] buffer=new short[800];
            input.startRecording();
            if (input.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("录音未开始，请检查麦克风权限");
            long lastMeter=0, lastSound=SystemClock.elapsedRealtime(),lastSignal=lastSound,lastHealth=0;
            boolean silentHint=false,noAudioHint=false,hotHint=false;
            while (capturing) {
                int n=input.read(buffer,0,buffer.length,AudioRecord.READ_BLOCKING);
                if (n<0) { if (capturing) throw new IllegalStateException("音频输入已中断 ("+n+")"); else break; }
                if (n==0) continue;
                pipeline.accept(buffer,n);
                long now=SystemClock.elapsedRealtime();
                for(int i=0;i<n;i++)if(Math.abs((int)buffer[i])>16){lastSignal=now;break;}
                if(now-lastHealth>5000){lastHealth=now;int heat=RuntimeHealth.thermal(this);
                    if(heat>=PowerManager.THERMAL_STATUS_CRITICAL)throw new IllegalStateException(AppPrefs.localized(this).getString(R.string.health_hot));
                    if(heat>=PowerManager.THERMAL_STATUS_SEVERE&&!hotHint)CaptionState.S.status(AppPrefs.localized(this).getString(R.string.health_warm));
                    hotHint=heat>=PowerManager.THERMAL_STATUS_SEVERE;
                }
                boolean noAudio=CaptionState.S.source.equals("手机声音")&&now-lastSignal>8000;
                if(noAudio&&!noAudioHint&&!hotHint)CaptionState.S.status(AppPrefs.localized(this).getString(R.string.no_audio));
                noAudioHint=noAudio;
                if (now-lastMeter>160) {
                    int level=Math.round(pipeline.speechProbability()*100); lastMeter=now;
                    if (level>=65) { lastSound=now; if(silentHint) { CaptionState.S.status("正在聆听 · 仅翻译人声"); silentHint=false; } }
                    if (now-lastSound>4000 && !silentHint && !noAudioHint && !hotHint) { CaptionState.S.status(CaptionState.S.source.equals("手机声音")?"等待人声 · 环境音过滤中；请播放所选语言的视频":"等待人声 · 环境音过滤中"); silentHint=true; }
                    CaptionState.MAIN.post(() -> { if(!capturing)return;CaptionState.S.level=level; CaptionState.S.changed(); });
                }
            }
            pipeline.finish();
        } catch (Exception e) { if (capturing) CaptionState.S.status("音频处理中断："+e.getMessage()); }
        finally {
            try { input.release(); } catch (Exception ignored) { }
            CaptionState.MAIN.post(()->stopSelf(startId));
        }
    }
    @Override public void onDestroy() {
        capturing=false;
        CaptionState.MAIN.removeCallbacks(idleCheck);idleCheckScheduled=false;
        AudioRecord r=recorder;
        if (r!=null) try { r.stop(); } catch (Exception ignored) { }
        if (projection!=null) { projection.stop(); projection=null; }
        if (wake!=null && wake.isHeld()) wake.release();
        CaptionState.S.running=false; CaptionState.S.level=0;
        if (CaptionState.S.status.startsWith("正在聆听") || CaptionState.S.status.startsWith("等待") || CaptionState.S.status.startsWith("未检测")) CaptionState.S.status="已停止 · 字幕保留在本次会话";
        CaptionState.S.unobserve(this); removeOverlay(); CaptionState.S.changed();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    private void heardSpeech(long now){
        // A new utterance must not revive the caption that expired during silence.
        if(now-lastSpeechAt>=OVERLAY_IDLE_MS)overlayClearedThrough=CaptionState.S.recognitionSequence();
        lastSpeechAt=now;
    }
    @Override public void changed() {
        long now=SystemClock.elapsedRealtime(),remaining=OVERLAY_IDLE_MS-(now-lastSpeechAt);
        boolean idle=remaining<=0;
        if(CaptionState.S.running){
            if(idle)overlayClearedThrough=CaptionState.S.recognitionSequence();
            else if(!idleCheckScheduled){idleCheckScheduled=true;CaptionState.MAIN.postDelayed(idleCheck,remaining);}
        }else{CaptionState.MAIN.removeCallbacks(idleCheck);idleCheckScheduled=false;}
        boolean show=CaptionState.S.running && CaptionState.S.overlay && Settings.canDrawOverlays(this) && BaseActivity.visibleActivities==0;
        if (!show) { removeOverlay(); return; }
        CaptionState.Line last=CaptionState.S.lines.isEmpty()?null:CaptionState.S.lines.get(CaptionState.S.lines.size()-1);
        String text=last==null?Ui.t(this,"等待所选语言的人声…","Waiting for speech in the selected language…"):last.zh.isEmpty()?(last.problem.isEmpty()?Ui.t(this,"正在翻译…","Translating…"):last.problem):last.zh;
        String source=last==null?"":last.en;
        if(idle||(last!=null&&last.recognitionSequence<=overlayClearedThrough)){text="";source="";}
        try{overlay.show(text,source);}catch(RuntimeException error){removeOverlay();CaptionState.S.status="悬浮字幕暂不可用，请检查悬浮窗权限";}
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);if(overlay!=null)overlay.configurationChanged();changed();}
    private void removeOverlay(){if(overlay!=null)overlay.hide();}
}
