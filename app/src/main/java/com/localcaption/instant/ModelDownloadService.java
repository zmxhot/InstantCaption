package com.localcaption.instant;

import android.app.*;
import android.content.*;
import android.os.*;
import android.content.pm.ServiceInfo;
import org.json.JSONObject;

/** Durable queue with explicit pause/resume and Android redelivery after interruption. */
public class ModelDownloadService extends Service implements CaptionState.Observer {
    private Thread worker;
    private volatile boolean paused;
    private boolean resumeRequested;
    private int latestStartId;
    static volatile boolean working;
    private int lastProgress=-2;
    private String lastPhase="";
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("models",Ui.t(this,"模型下载","Model downloads"),NotificationManager.IMPORTANCE_LOW));CaptionState.S.observe(this);}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        latestStartId=id;
        if(intent!=null&&"cancel".equals(intent.getAction())){resumeRequested=false;pause();if(worker==null)stopSelf(id);return START_NOT_STICKY;}
        startForeground(2,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if(worker!=null){if(paused)resumeRequested=true;return START_REDELIVER_INTENT;}
        boolean deleting=intent!=null&&intent.getBooleanExtra("delete",false);
        String requested=intent==null?null:intent.getStringExtra("model");
        if(deleting){
            if(!DownloadJournal.known(requested)||LocalEngine.get(this).configurationBusy()){stopSelf();return START_NOT_STICKY;}
        }else{
            if(requested!=null&&!DownloadJournal.hasJobs(this))DownloadJournal.enqueue(this,new String[]{requested},intent.getIntExtra("source",0),false,null);
            if(!DownloadJournal.hasJobs(this)||LocalEngine.get(this).configurationBusy()){DownloadJournal.state(this,false);stopSelf();return START_NOT_STICKY;}
            DownloadJournal.state(this,true);
        }
        working=true;paused=false;
        worker=new Thread(()->{
            try{
                if(deleting){if(execute(requested,true,0,""))DownloadJournal.completed(this,requested);return;}
                while(!paused){
                    JSONObject job=DownloadJournal.first(this);if(job==null)break;
                    String model=job.optString("model"),tree=job.optString("tree");
                    if(!job.optBoolean("metered")&&tree.isEmpty()&&DownloadActions.metered(this)){
                        ModelStore.phase="paused";ModelStore.error=AppPrefs.localized(this).getString(R.string.wifi_required);break;
                    }
                    ModelStore.networkContext=getApplicationContext();ModelStore.allowMetered=job.optBoolean("metered");
                    if(!execute(model,false,job.optInt("source"),tree))break;
                    DownloadJournal.completed(this,model);
                }
            }finally{
                ModelStore.networkContext=null;
                CaptionState.MAIN.post(()->{
                    worker=null;working=false;DownloadJournal.state(this,false);
                    if(resumeRequested&&DownloadJournal.hasJobs(this)){resumeRequested=false;onStartCommand(new Intent(this,ModelDownloadService.class).setAction("queue"),0,latestStartId);return;}
                    stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(latestStartId);CaptionState.S.changed();
                    if(!DownloadJournal.hasJobs(this)&&!deleting)LocalEngine.get(this).prepare();
                });
            }
        },"model-install");worker.start();return deleting?START_NOT_STICKY:START_REDELIVER_INTENT;
    }
    private boolean execute(String id,boolean deleting,int source,String tree){
        String target=TranslationModel.targetFromId(id);QwenModel qwen=QwenModel.fromId(id);String action=deleting?"delete":"download";
        if(paused||!DownloadJournal.known(id))return false;
        try{ModelStore.recoverPrevious(this,id);}catch(java.io.IOException e){ModelStore.error=e.getMessage();ModelStore.phase="failed";return false;}
        boolean reserved=qwen!=null?ModelStore.reserveQwen(this,action,qwen):target!=null?ModelStore.reserveTranslation(this,action,target):ModelStore.reserve(NpuModel.fromId(id),action);
        if(!reserved)return false;
        if(!tree.isEmpty()&&!deleting)ModelStore.runImport(this,id,android.net.Uri.parse(tree));
        else if(qwen!=null||target!=null)ModelStore.runTranslation(this,deleting,source);else ModelStore.run(this,deleting,source);
        return ModelStore.phase.equals("done")||ModelStore.phase.equals("deleted");
    }
    private void pause(){paused=true;DownloadJournal.state(this,false);ModelStore.cancel();}
    private Notification notification(){
        Context c=AppPrefs.localized(this);
        PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,ModelManagerActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(this,4,new Intent(this,ModelDownloadService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE);
        int progress=ModelStore.total>0?(int)(100*ModelStore.downloaded/ModelStore.total):0;
        return new Notification.Builder(this,"models").setSmallIcon(R.drawable.ic_app).setContentTitle(c.getString(R.string.download_models)).setContentText(ModelStore.phaseText(c)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(100,progress,!ModelStore.phase.equals("download")||ModelStore.total==0).addAction(new Notification.Action.Builder(null,c.getString(R.string.pause_transfer),cancel).build()).build();
    }
    @Override public void changed(){if(!working||!ModelStore.active)return;int progress=ModelStore.total>0?(int)(100*ModelStore.downloaded/ModelStore.total):-1;if(progress==lastProgress&&lastPhase.equals(ModelStore.phase))return;lastProgress=progress;lastPhase=ModelStore.phase;getSystemService(NotificationManager.class).notify(2,notification());}
    @Override public void onTimeout(int startId,int type){pause();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){if(worker!=null&&worker.isAlive())pause();CaptionState.S.unobserve(this);super.onDestroy();}
}
