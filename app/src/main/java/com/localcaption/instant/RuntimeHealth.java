package com.localcaption.instant;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

final class RuntimeHealth {
    static long availableMemory(Context c){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();c.getSystemService(ActivityManager.class).getMemoryInfo(info);return info.availMem;}
    static boolean lowMemory(Context c){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();c.getSystemService(ActivityManager.class).getMemoryInfo(info);return info.lowMemory;}
    static int thermal(Context c){return c.getSystemService(PowerManager.class).getCurrentThermalStatus();}
    static void beforeLoad(Context c,QwenModel model)throws ModelFailure {
        long minimum=model==null?192_000_000L:model==QwenModel.GEMMA4?4_000_000_000L:model==QwenModel.LARGE?2_800_000_000L:model==QwenModel.HYMT2?1_800_000_000L:model==QwenModel.GEMMA3?1_800_000_000L:model==QwenModel.BALANCED?1_600_000_000L:800_000_000L;
        if(lowMemory(c)||availableMemory(c)<minimum)throw new ModelFailure(ModelFailure.Kind.MEMORY);
        if(thermal(c)>=PowerManager.THERMAL_STATUS_CRITICAL)throw new ModelFailure(ModelFailure.Kind.HOT);
    }
    static void loaded(Context c,String id){c.getSharedPreferences("runtime-health",0).edit().putString(id,Build.FINGERPRINT+":"+BuildConfig.VERSION_CODE).apply();}
    static boolean verifiedLoad(Context c,String id){return (Build.FINGERPRINT+":"+BuildConfig.VERSION_CODE).equals(c.getSharedPreferences("runtime-health",0).getString(id,""));}
    static String label(Context c,String id){return c.getString(verifiedLoad(c,id)?R.string.load_verified:R.string.load_unverified);}
}
