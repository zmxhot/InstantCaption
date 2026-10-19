package com.localcaption.instant;
import android.app.Activity;
import android.content.Context;
public abstract class BaseActivity extends Activity {
    static int visibleActivities;
    private boolean resumed;
    private String language;
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(AppPrefs.localized(base));}
    @Override protected void onCreate(android.os.Bundle b){super.onCreate(b);language=AppPrefs.language(this);}
    @Override protected void onResume(){super.onResume();if(!resumed){resumed=true;visibleActivities++;CaptionState.S.changed();}if(!AppPrefs.language(this).equals(language))recreate();}
    @Override protected void onPause(){if(resumed){resumed=false;visibleActivities=Math.max(0,visibleActivities-1);CaptionState.S.changed();}super.onPause();}
    String tr(String zh,String en){return Ui.t(this,zh,en);}
    int dp(float n){return Ui.dp(this,n);}
}
