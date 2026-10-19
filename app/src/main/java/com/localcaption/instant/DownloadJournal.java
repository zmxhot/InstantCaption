package com.localcaption.instant;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable user-authorized jobs; never persists transcript, audio or deletions. */
@android.annotation.SuppressLint("ApplySharedPref") // Queue transitions must survive process death before returning.
final class DownloadJournal {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("downloads-v1",0);}
    static synchronized JSONArray jobs(Context c){try{return new JSONArray(prefs(c).getString("jobs","[]"));}catch(Exception e){return new JSONArray();}}
    static synchronized boolean hasJobs(Context c){return jobs(c).length()>0;}
    static boolean running(Context c){return hasJobs(c)&&prefs(c).getBoolean("running",false);}
    static synchronized void enqueue(Context c,String[] ids,int channel,boolean metered,String tree){
        JSONArray jobs=jobs(c);
        try{for(String id:ids){if(!known(id))throw new IllegalArgumentException("Unknown model");boolean exists=false;for(int n=0;n<jobs.length();n++)if(id.equals(jobs.getJSONObject(n).getString("model"))){exists=true;jobs.getJSONObject(n).put("source",channel).put("metered",metered).put("tree",tree==null?"":tree);}
            if(!exists)jobs.put(new JSONObject().put("model",id).put("source",channel).put("metered",metered).put("tree",tree==null?"":tree));}
            if(!prefs(c).edit().putString("jobs",jobs.toString()).commit())throw new IllegalStateException("Cannot save download queue");
        }catch(org.json.JSONException e){throw new IllegalArgumentException(e);}
    }
    static boolean known(String id){if(QwenModel.known(id)||TranslationModel.targetFromId(id)!=null)return true;for(NpuModel m:NpuModel.values())if(m.id.equals(id))return true;return false;}
    static synchronized JSONObject first(Context c){return jobs(c).optJSONObject(0);}
    static synchronized void completed(Context c,String id){JSONArray old=jobs(c),next=new JSONArray();for(int n=0;n<old.length();n++){JSONObject j=old.optJSONObject(n);if(j!=null&&!id.equals(j.optString("model")))next.put(j);}prefs(c).edit().putString("jobs",next.toString()).commit();}
    static synchronized void changeSource(Context c,int source){JSONArray jobs=jobs(c);for(int i=0;i<jobs.length();i++)try{jobs.getJSONObject(i).put("source",source);}catch(org.json.JSONException ignored){}prefs(c).edit().putString("jobs",jobs.toString()).commit();}
    static synchronized void allowMetered(Context c){JSONArray jobs=jobs(c);for(int i=0;i<jobs.length();i++)try{jobs.getJSONObject(i).put("metered",true);}catch(org.json.JSONException ignored){}prefs(c).edit().putString("jobs",jobs.toString()).commit();}
    static synchronized void state(Context c,boolean active){prefs(c).edit().putBoolean("running",active).commit();}
    static synchronized void clear(Context c){if(!ModelStore.active)prefs(c).edit().clear().commit();}
}
