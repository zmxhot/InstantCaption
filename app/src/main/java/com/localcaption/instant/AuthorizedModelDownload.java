package com.localcaption.instant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

/** The publisher's own website handles login and terms; this app never reads account tokens. */
final class AuthorizedModelDownload {
    static void show(Activity a,QwenModel model){
        try{
            String url=model.manifest(a).getJSONObject("download").getString("authorizationUrl");
            Uri uri=Uri.parse(url);
            if(!"https".equals(uri.getScheme())||!"huggingface.co".equals(uri.getHost()))throw new IllegalArgumentException("Invalid authorization host");
            new AlertDialog.Builder(a).setTitle(R.string.authorized_download).setMessage(a.getString(R.string.authorized_download_help)+"\n\n"+model.compatibility(a)+"\n"+model.entry(a).getName())
                .setNegativeButton(android.R.string.cancel,null)
                .setNeutralButton(R.string.import_model_file,(d,w)->a.startActivity(new Intent(a,ModelManagerActivity.class).putExtra("tab",0).putExtra("importRawModel",model.id)))
                .setPositiveButton(R.string.open_official_page,(d,w)->{try{a.startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(android.content.ActivityNotFoundException e){new AlertDialog.Builder(a).setMessage(url).setPositiveButton(android.R.string.ok,null).show();}}).show();
        }catch(Exception e){new AlertDialog.Builder(a).setMessage(e.getMessage()).setPositiveButton(android.R.string.ok,null).show();}
    }
}
