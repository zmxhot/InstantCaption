package com.localcaption.instant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DownloadActions {
    static boolean metered(Context c){return c.getSystemService(ConnectivityManager.class).isActiveNetworkMetered();}
    static long downloadBytes(Context c,String id)throws Exception{QwenModel q=QwenModel.fromId(id);if(q!=null)return q.manifest(c).getJSONObject("download").getLong("size");String t=TranslationModel.targetFromId(id);return t!=null?TranslationModel.bytes(c,t):ModelStore.spec(c,NpuModel.fromId(id)).getJSONObject("download").getLong("size");}
    static long installedBytes(Context c,String id){QwenModel q=QwenModel.fromId(id);if(q!=null)return q.installedBytes(c);String t=TranslationModel.targetFromId(id);return t!=null?TranslationModel.bytes(c,t):ModelStore.bytes(c,NpuModel.fromId(id));}
    static void confirm(Activity a,String[] ids){
        try{
            for(String id:ids){QwenModel model=QwenModel.fromId(id);if(model!=null&&model.requiresAuthorization()){AuthorizedModelDownload.show(a,model);return;}}
            long bytes=0,space=32_000_000;for(String id:ids){bytes+=downloadBytes(a,id);space+=downloadBytes(a,id)+installedBytes(a,id);}
            LinearLayout box=Ui.column(a);box.setPadding(Ui.dp(a,20),Ui.dp(a,8),Ui.dp(a,20),0);
            TextView description=Ui.text(a,a.getString(R.string.download_summary,Ui.size(bytes),Ui.size(space)),14,Ui.WHITE);box.addView(description);
            for(String id:ids){QwenModel model=QwenModel.fromId(id);if(model!=null&&"qairt".equals(model.runtime)){
                TextView terms=Ui.text(a,a.getString(R.string.qwen_publisher_terms)+"\nhttps://huggingface.co/qualcomm/"+(model==QwenModel.SMALL?"Qwen3-0.6B":model==QwenModel.LARGE?"Qwen3-4B":"Qwen3-1.7B"),13,Ui.WHITE);
                terms.setPadding(0,Ui.dp(a,12),0,Ui.dp(a,8));android.text.util.Linkify.addLinks(terms,android.text.util.Linkify.WEB_URLS);terms.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());box.addView(terms);break;
            }}
            CheckBox mobile=new CheckBox(a);mobile.setText(R.string.allow_metered);mobile.setTextColor(Ui.WHITE);mobile.setChecked(false);box.addView(mobile);
            android.widget.ScrollView scroll=new android.widget.ScrollView(a);scroll.addView(box);
            new AlertDialog.Builder(a).setTitle(R.string.download_models).setView(scroll).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.download_models,(d,w)->{
                DownloadJournal.enqueue(a,ids,AppPrefs.prefs(a).getInt("downloadSource",0),mobile.isChecked(),null);resume(a);
            }).show();
        }catch(Exception error){new AlertDialog.Builder(a).setMessage(error.toString()).setPositiveButton(android.R.string.ok,null).show();}
    }
    static void resume(Context c){
        if(LocalEngine.get(c).configurationBusy())return;
        org.json.JSONObject job=DownloadJournal.first(c);
        if(c instanceof Activity&&job!=null&&job.optString("tree").isEmpty()&&!job.optBoolean("metered")&&metered(c)){
            new AlertDialog.Builder(c).setTitle(R.string.transfer_paused).setMessage(R.string.wifi_required).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.allow_metered,(d,w)->{DownloadJournal.allowMetered(c);resume(c);}).show();return;
        }
        DownloadJournal.state(c,true);
        try{c.startForegroundService(new Intent(c,ModelDownloadService.class).setAction("queue"));}
        catch(RuntimeException e){DownloadJournal.state(c,false);CaptionState.S.status(AppPrefs.localized(c).getString(R.string.transfer_paused));}
    }
}
