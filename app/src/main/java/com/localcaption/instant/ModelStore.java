package com.localcaption.instant;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;
import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

/** Only known, hash-pinned model files are installed. No audio uses this network path. */
final class ModelStore {
    static volatile NpuModel job;
    static volatile boolean active,cancel,translationJob;
    static volatile String phase="",channel="",error="",fileName="";
    static volatile long downloaded,total;
    static volatile String translationJobLabel="";
    static volatile String translationJobTarget="";
    private static volatile HttpURLConnection connection;
    @android.annotation.SuppressLint("StaticFieldLeak") // Application context only, cleared when the transfer worker exits.
    static volatile Context networkContext;
    static volatile boolean allowMetered;
    private static long nextNetworkCheck;
    private static void checkNetwork()throws IOException{
        if(networkContext==null||allowMetered)return;long now=android.os.SystemClock.elapsedRealtime();if(now<nextNetworkCheck)return;nextNetworkCheck=now+1000;
        if(DownloadActions.metered(networkContext))throw new IOException(AppPrefs.localized(networkContext).getString(R.string.wifi_required));
    }
    private static final Map<String,JSONObject> catalogs=new HashMap<>();
    static JSONObject spec(Context c,NpuModel m)throws Exception{return spec(c,m,DeviceProfile.detect(c));}
    static synchronized JSONObject spec(Context c,NpuModel m,DeviceProfile device)throws Exception{
        String asset=device.metadata(m.id).manifest;
        JSONObject catalog=catalogs.get(asset);
        if(catalog==null)try(InputStream in=c.getAssets().open(asset)){catalog=new JSONObject(NpuRecognizer.readText(in));catalogs.put(asset,catalog);}
        return catalog.getJSONObject(m.id);
    }
    static File root(Context c,NpuModel m){return new File(c.getExternalFilesDir(null),m.directory(DeviceProfile.detect(c)));}
    static File compiledRoot(Context c,NpuModel m){return new File(c.getFilesDir(),m.directory(DeviceProfile.detect(c)));}
    static boolean installed(Context c,NpuModel m){try{JSONArray a=spec(c,m).getJSONArray("files");for(int i=0;i<a.length();i++){JSONObject f=a.getJSONObject(i);if(new File(root(c,m),f.getString("name")).length()!=f.getLong("size"))return false;}return true;}catch(Exception e){return false;}}
    static long bytes(Context c,NpuModel m){try{JSONArray a=spec(c,m).getJSONArray("files");long n=0;for(int i=0;i<a.length();i++)n+=a.getJSONObject(i).getLong("size");return n;}catch(Exception e){return 0;}}
    static boolean busy(NpuModel m){return active&&!translationJob&&job==m;}
    static synchronized boolean reserve(NpuModel m,String action){if(m==null||active||CaptionState.S.running||CaptionState.S.preparing)return false;job=m;translationJob=false;begin(action);return true;}
    private static synchronized boolean reserveTranslation(String action){if(active||CaptionState.S.running||CaptionState.S.preparing)return false;job=null;translationJob=true;begin(action);return true;}
    private static void begin(String action){active=true;cancel=false;phase=action;channel="";error="";fileName="";downloaded=0;total=0;notifyUi();}
    static synchronized boolean reserveTranslation(Context c,String action){return reserveTranslation(c,action,TranslationModel.target(c));}
    static synchronized boolean reserveTranslation(Context c,String action,String target){
        if(!TranslationModel.supportsTarget(target)||LocalEngine.get(c).configurationBusy())return false;
        if(!"delete".equals(action)&&!DeviceProfile.detect(c).supportsText("opus"))return false;
        translationJobTarget=target;translationJobLabel=TranslationModel.label(c,target);return reserveTranslation(action);
    }
    static synchronized boolean reserveQwen(Context c,String action){return reserveQwen(c,action,QwenModel.BALANCED);}
    static synchronized boolean reserveQwen(Context c,String action,QwenModel model){
        if(LocalEngine.get(c).configurationBusy()||(!"delete".equals(action)&&!model.isSupported(c)))return false;
        translationJobTarget=model.id;translationJobLabel=model.label+" · "+model.processor();return reserveTranslation(action);
    }
    static String jobLabel(Context c){return translationJob?(translationJobLabel.isEmpty()?TranslationModel.label(c):translationJobLabel):job==null?Ui.t(c,"模型","Models"):job.label(c);}
    static void notifyUi(){CaptionState.MAIN.post(()->CaptionState.S.changed());}
    static void progress(String p){phase=p;notifyUi();}
    static void cancel(){cancel=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    private static void check(){if(cancel||Thread.currentThread().isInterrupted())throw new CancellationException();}
    static String channelName(Context c,int choice){return choice==0?Ui.t(c,"自动切换","Automatic fallback"):choice==1?Ui.t(c,"官方源","Official source"):Ui.t(c,"备用镜像","Backup mirror");}
    static String phaseText(Context c){
        switch(phase){case "paused":return c.getString(R.string.transfer_paused);case "download":return Ui.t(c,"正在下载","Downloading");case "install":return Ui.t(c,"校验并安装中","Verifying and installing");case "delete":return Ui.t(c,"正在删除模型及缓存","Removing model and cache");case "done":return Ui.t(c,"安装完成","Installed");case "deleted":return Ui.t(c,"模型及缓存已删除","Model and cache removed");case "cancelled":return Ui.t(c,"已取消，可重新下载","Cancelled · tap download to resume");case "failed":return Ui.t(c,"操作失败，可重试或切换通道","Failed · retry or choose another channel");default:return "";}
    }
    static void run(Context c,boolean deleting,int source){
        NpuModel m=job;
        try{
            if(deleting){
                if(!LocalEngine.get(c).unloadForRemoval(m))throw new IOException(Ui.t(c,"请先停止字幕，等待加载完成","Stop captions and wait for model loading"));
                erase(c,compiledRoot(c,m),c.getFilesDir());erase(c,root(c,m),c.getExternalFilesDir(null));erase(c,new File(downloadRoot(c),m.id),downloadRoot(c));progress("deleted");
            }else install(c,m,source);
        }catch(CancellationException e){progress("cancelled");}
        catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();progress(cancel?"cancelled":"failed");}
        finally{connection=null;active=false;notifyUi();if(phase.equals("done")&&!DownloadJournal.running(c)&&LocalEngine.get(c).selectedModel()==m)CaptionState.MAIN.post(()->LocalEngine.get(c).prepare());}
    }
    static void runTranslation(Context c,boolean deleting,int source){
        final String target=translationJobTarget;
        try{
            if(QwenModel.known(target)){
                QwenModel model=QwenModel.fromId(target);
                if(deleting){
                    if(!LocalEngine.get(c).unloadQwenTranslation(model.id))throw new IOException("Translation model is in use");
                    erase(c,model.modelRoot(c),c.getExternalFilesDir(null));erase(c,new File(downloadRoot(c),model.id),downloadRoot(c));progress("deleted");
                }else installQwen(c,source,model);
            }else if(deleting){
                if(!LocalEngine.get(c).unloadTranslation(target))throw new IOException(Ui.t(c,"请先停止字幕，等待加载完成","Stop captions and wait for model loading"));
                erase(c,TranslationModel.root(c,target),c.getExternalFilesDir(null));
                erase(c,new File(c.getFilesDir(),TranslationModel.id(target)),c.getFilesDir());
                erase(c,new File(downloadRoot(c),TranslationModel.id(target)),downloadRoot(c));progress("deleted");
            }else installTranslation(c,source,target);
        }catch(CancellationException e){progress("cancelled");}
        catch(Exception e){error=e.getMessage()==null?e.toString():e.getMessage();progress(cancel?"cancelled":"failed");}
        finally{connection=null;active=false;notifyUi();if(phase.equals("done")&&!DownloadJournal.running(c)&&(QwenModel.known(target)?target.equals(LocalEngine.get(c).translationChoice()):target.equals(TranslationModel.target(c))))CaptionState.MAIN.post(()->LocalEngine.get(c).prepare());}
    }
    private static void installQwen(Context c,int source,QwenModel model)throws Exception{
        if(!model.isSupported(c))throw new IOException(model.compatibility(c));
        JSONObject spec=model.manifest(c),archive=spec.getJSONObject("download");
        if(archive.optBoolean("requiresAuthorization"))throw new IOException(AppPrefs.localized(c).getString(R.string.authorized_download_help));
        File work=new File(downloadRoot(c),model.id);if(!work.isDirectory()&&!work.mkdirs())throw new IOException("Cannot create download folder");
        File part=new File(work,"archive.part"),stage=new File(work,"staging");
        if(work.getUsableSpace()+part.length()<archive.getLong("size")+model.installedBytes(c)+32_000_000L)throw new IOException(Ui.t(c,"存储空间不足，请释放空间后重试","Not enough storage space"));
        fetchArchive(archive,source,part);check();progress("install");
        try{
            erase(c,stage,work);if(!stage.mkdirs())throw new IOException("Cannot create staging folder");
            unpackTextArchive(part,stage,spec);
            if(!LocalEngine.get(c).unloadQwenTranslation(model.id))throw new IOException("Translation model is in use");
            File target=model.modelRoot(c),old=new File(work,"previous");erase(c,old,work);boolean moved=target.exists();
            if(moved&&!target.renameTo(old))throw new IOException("Cannot back up Qwen model");
            if(!stage.renameTo(target)){if(moved&&!old.renameTo(target))throw new IOException("Previous model retained in model-downloads/"+model.id+"/previous");throw new IOException("Cannot install Qwen model");}
            erase(c,old,work);if(!part.delete())android.util.Log.w("ModelStore","Qwen archive retained for cleanup");fileName="";progress("done");
        }finally{erase(c,stage,work);}
    }
    static void unpackTextArchive(File part,File stage,JSONObject spec)throws Exception{
        JSONObject archive=spec.getJSONObject("download");
        JSONArray files=spec.getJSONArray("files");Map<String,JSONObject> wanted=new HashMap<>();Set<String> seen=new HashSet<>();
        for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String name=f.getString("name");modelFile(stage,name);if(wanted.put(name,f)!=null)throw new IOException("Duplicate model file");}
        if("raw".equals(archive.optString("format"))){
            if(files.length()!=1)throw new IOException("Raw model must contain one file");
            JSONObject file=files.getJSONObject(0);String name=file.getString("name");
            fileName=name;notifyUi();
            try(InputStream in=new FileInputStream(part)){extract(in,modelFile(stage,name),part.length(),file);}
            seen.add(name);
        }else try(ZipFile zip=new ZipFile(part)){
            Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){
                check();ZipEntry entry=entries.nextElement();if(entry.isDirectory())continue;
                String name=entry.getName(),base=name.substring(name.lastIndexOf('/')+1);if(!wanted.containsKey(base))continue;
                if(!seen.add(base))throw new IOException("Duplicate model file");fileName=base;notifyUi();
                try(InputStream in=zip.getInputStream(entry)){extract(in,modelFile(stage,base),entry.getSize(),wanted.get(base));}
            }
        }
        if(seen.size()!=wanted.size())throw new IOException("Missing model files");check();
    }
    static File downloadRoot(Context c){return new File(c.getExternalFilesDir(null),"model-downloads");}
    static void recoverPrevious(Context c,String id)throws IOException{
        QwenModel q=QwenModel.fromId(id);String language=TranslationModel.targetFromId(id);
        File target=q!=null?q.modelRoot(c):language!=null?TranslationModel.root(c,language):root(c,NpuModel.fromId(id));
        File previous=new File(new File(downloadRoot(c),id),"previous");
        if(!target.exists()&&previous.isDirectory()&&!previous.renameTo(target))throw new IOException("Cannot restore previous model");
    }
    static void runImport(Context c,String id,android.net.Uri tree){
        File work=new File(downloadRoot(c),id),stage=new File(work,"import-staging");
        try{
            recoverPrevious(c,id);QwenModel q=QwenModel.fromId(id);String language=TranslationModel.targetFromId(id);NpuModel speech=q==null&&language==null?NpuModel.fromId(id):null;
            if(q!=null&&!q.isSupported(c)||speech!=null&&!DeviceProfile.detect(c).supports(speech)||language!=null&&!DeviceProfile.detect(c).supportsText("opus"))throw new IOException("Model incompatible with this device");
            JSONObject spec=q!=null?q.manifest(c):language!=null?TranslationModel.spec(c,language):spec(c,speech);JSONArray files=spec.getJSONArray("files");
            if(!work.isDirectory()&&!work.mkdirs())throw new IOException("Cannot create import folder");
            long needed=DownloadActions.installedBytes(c,id)+32_000_000L;if(work.getUsableSpace()<needed)throw new IOException("Not enough storage space");
            erase(c,stage,work);if(!stage.mkdirs())throw new IOException("Cannot create staging folder");
            Set<String> names=new HashSet<>();for(int i=0;i<files.length();i++)names.add(files.getJSONObject(i).getString("name"));
            Map<String,android.net.Uri> sources=DocumentModelImport.find(c,tree,names);if(sources.size()!=names.size())throw new IOException("Missing model files; select the extracted model folder");
            progress("install");total=DownloadActions.installedBytes(c,id);downloaded=0;
            for(int i=0;i<files.length();i++){
                check();JSONObject file=files.getJSONObject(i);fileName=file.getString("name");notifyUi();
                try(InputStream in=c.getContentResolver().openInputStream(sources.get(fileName))){if(in==null)throw new IOException("Cannot read model file");extract(in,modelFile(stage,fileName),file.getLong("size"),file);}
                downloaded+=file.getLong("size");notifyUi();
            }
            check();LocalEngine engine=LocalEngine.get(c);if(!(q!=null?engine.unloadQwenTranslation(q.id):language!=null?engine.unloadTranslation(language):engine.unloadForRemoval(speech)))throw new IOException("Model is in use");
            File target=q!=null?q.modelRoot(c):language!=null?TranslationModel.root(c,language):root(c,speech),old=new File(work,"previous");erase(c,old,work);
            boolean moved=target.exists();if(moved&&!target.renameTo(old))throw new IOException("Cannot back up model");
            if(!stage.renameTo(target)){if(moved)old.renameTo(target);throw new IOException("Cannot install imported model");}
            erase(c,old,work);fileName="";progress("done");
        }catch(CancellationException e){progress("cancelled");}catch(Exception e){error=e.getMessage();progress("failed");}
        finally{try{erase(c,stage,work);}catch(IOException ignored){}active=false;notifyUi();}
    }
    private static File modelFile(File folder,String name) throws IOException{
        if(name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0||!new File(folder,name).getCanonicalFile().getParentFile().equals(folder.getCanonicalFile()))throw new IOException("Invalid model filename");
        return new File(folder,name);
    }
    private static void installTranslation(Context c,int source,String language) throws Exception{
        if(!DeviceProfile.detect(c).supportsText("opus"))throw new IOException("Model is incompatible with this device");
        if(!TranslationModel.supportsTarget(language))throw new IOException("No independent translation model for this target language");
        JSONArray files=TranslationModel.spec(c,language).getJSONArray("files");
        File work=new File(downloadRoot(c),TranslationModel.id(language)),stage=new File(work,"staging");
        if(!stage.isDirectory()&&!stage.mkdirs())throw new IOException("Cannot create staging folder");
        Set<String> allowed=new HashSet<>();long reusable=0;
        for(int i=0;i<files.length();i++){
            JSONObject f=files.getJSONObject(i);String name=f.getString("name");modelFile(stage,name);modelFile(stage,name+".part");
            if(!allowed.add(name)||!allowed.add(name+".part"))throw new IOException("Duplicate model filename");
            reusable+=Math.min(f.getLong("size"),new File(stage,name).length()+new File(stage,name+".part").length());
        }
        File[] previousFiles=stage.listFiles();if(previousFiles==null)throw new IOException("Cannot read staging folder");
        for(File stale:previousFiles)if(!allowed.contains(stale.getName())||!stale.isFile())erase(c,stale,work);
        if(stage.getUsableSpace()+reusable<TranslationModel.bytes(c,language)+32_000_000L)throw new IOException(Ui.t(c,"存储空间不足，请释放空间后重试","Not enough storage space"));
        for(int i=0;i<files.length();i++){
            check();JSONObject f=files.getJSONObject(i);fileName=(i+1)+"/"+files.length()+" · "+f.getString("name");notifyUi();
            File complete=modelFile(stage,f.getString("name")),part=modelFile(stage,f.getString("name")+".part");
            if(complete.length()==f.getLong("size")&&sha(complete).equals(f.getString("sha256"))){erase(c,part,work);continue;}
            erase(c,complete,work);fetchArchive(f,source,part);check();
            if(!part.renameTo(complete))throw new IOException("Cannot stage model file");
        }
        check();progress("install");
        if(!LocalEngine.get(c).unloadTranslation(language))throw new IOException("Translation model is in use");
        File target=TranslationModel.root(c,language),old=new File(work,"previous");erase(c,old,work);
        boolean moved=target.exists();if(moved&&!target.renameTo(old))throw new IOException("Cannot back up previous translation model");
        if(!stage.renameTo(target)){if(moved&&!old.renameTo(target))throw new IOException("Install failed; previous translation model is in model-downloads/"+TranslationModel.id(language)+"/previous");throw new IOException("Cannot install translation model");}
        erase(c,old,work);fileName="";progress("done");
    }
    private static void install(Context c,NpuModel m,int source) throws Exception{
        if(!DeviceProfile.detect(c).supports(m))throw new IOException("Model is incompatible with this device");
        JSONObject spec=spec(c,m),archive=spec.getJSONObject("download");JSONArray urls=archive.getJSONArray("urls");
        File work=new File(downloadRoot(c),m.id);if(!work.isDirectory()&&!work.mkdirs())throw new IOException("Cannot create download folder");
        long needed=archive.getLong("size")+bytes(c,m)+32_000_000L;
        File part=new File(work,"archive.part");if(work.getUsableSpace()+part.length()<needed)throw new IOException(Ui.t(c,"存储空间不足，请释放空间后重试","Not enough storage space"));
        File stage=new File(work,"staging");
        fetchArchive(archive,source,part);check();progress("install");
        try{
            erase(c,stage,work);if(!stage.mkdirs())throw new IOException("Cannot create staging folder");
            unpack(c,m,part,stage,archive.getString("format"));check();
            if(!LocalEngine.get(c).unloadForRemoval(m))throw new IOException("Model is in use");
            File target=root(c,m),old=new File(work,"previous");erase(c,old,work);
            boolean moved=target.exists();if(moved&&!target.renameTo(old))throw new IOException("Cannot back up previous model");
            if(!stage.renameTo(target)){if(moved&&!old.renameTo(target))throw new IOException("Install failed; previous model is in model-downloads/"+m.id+"/previous");throw new IOException("Cannot install model");}
            erase(c,old,work);if(!part.delete())android.util.Log.w("ModelStore","Archive retained for cleanup");progress("done");
        }finally{erase(c,stage,work);}
    }
    static void fetchArchive(JSONObject archive,int source,File part) throws Exception{
        JSONArray urls=archive.getJSONArray("urls");Exception last=null;
        int first=source==2?1:0,limit=source==1?1:urls.length();
        for(int i=first;i<limit;i++){
            if(i>first){long until=android.os.SystemClock.elapsedRealtime()+Math.min(4000,500L<<(i-first-1));while(android.os.SystemClock.elapsedRealtime()<until){check();checkNetwork();Thread.sleep(100);}}
            check();JSONObject endpoint=urls.getJSONObject(i);channel=endpoint.getString("name");progress("download");
            try{
                download(endpoint.getString("url"),part,archive.getLong("size"));check();progress("install");
                if(!sha(part).equals(archive.getString("sha256"))){if(!part.delete())throw new IOException("Cannot reset invalid download");throw new IOException("Archive checksum mismatch");}
                return;
            }catch(CancellationException e){throw e;}catch(Exception e){last=e;if(cancel)throw new CancellationException();checkNetwork();android.util.Log.w("ModelDownloads",channel+": "+e.getClass().getSimpleName());}
        }
        if(last!=null)throw last;throw new IOException("No download source");
    }
    static void download(String address,File dest,long expected) throws Exception{
        checkNetwork();
        if(dest.length()>expected&&!dest.delete())throw new IOException("Cannot reset download");
        if(dest.length()==expected){downloaded=total=expected;notifyUi();return;}
        long offset=dest.length();URL url=new URL(address);if(!url.getProtocol().equals("https"))throw new IOException("HTTPS required");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();connection=c;c.setConnectTimeout(12000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","InstantCaption/"+BuildConfig.VERSION_NAME+" Android");c.setRequestProperty("Accept-Encoding","identity");if(offset>0)c.setRequestProperty("Range","bytes="+offset+"-");
        try{
            int code=c.getResponseCode();if(!c.getURL().getProtocol().equals("https"))throw new IOException("HTTPS required");
            if(code!=200&&code!=206)throw new IOException("HTTP "+code);
            if(code==206&&(c.getHeaderField("Content-Range")==null||!c.getHeaderField("Content-Range").startsWith("bytes "+offset+"-")))throw new IOException("Invalid download range");
            boolean append=code==206&&offset>0;if(!append)offset=0;total=expected;downloaded=offset;long last=0;
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(dest,append)){
                byte[] buffer=new byte[131072];int n;while((n=in.read(buffer))!=-1){check();checkNetwork();if(downloaded+n>expected)throw new IOException("Download exceeds expected size");out.write(buffer,0,n);downloaded+=n;long now=android.os.SystemClock.elapsedRealtime();if(now-last>350){last=now;notifyUi();}}out.getFD().sync();
            }
            if(downloaded!=expected)throw new IOException("Incomplete download "+downloaded+"/"+expected);
        }finally{c.disconnect();connection=null;}
    }
    private static String sha(File file) throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1){check();d.update(b,0,n);}}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    static void unpack(Context c,NpuModel model,File archive,File stage,String format) throws Exception{
        JSONArray a=spec(c,model).getJSONArray("files");Map<String,JSONObject> wanted=new HashMap<>();Set<String> seen=new HashSet<>();for(int i=0;i<a.length();i++){JSONObject f=a.getJSONObject(i);wanted.put(f.getString("name"),f);}
        if(format.equals("zip")){
            try(ZipFile zip=new ZipFile(archive)){Enumeration<? extends ZipEntry> all=zip.entries();while(all.hasMoreElements()){check();ZipEntry entry=all.nextElement();String name=entry.getName();if(entry.isDirectory()||!name.startsWith("assets/"))continue;String base=name.substring(name.lastIndexOf('/')+1);if(wanted.containsKey(base)){if(!seen.add(base))throw new IOException("Duplicate model file");try(InputStream in=zip.getInputStream(entry)){extract(in,new File(stage,base),entry.getSize(),wanted.get(base));}}}}
        }else{
            try(TarArchiveInputStream tar=new TarArchiveInputStream(new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(archive),131072)))){TarArchiveEntry entry;while((entry=tar.getNextEntry())!=null){check();String name=entry.getName(),base=name.substring(name.lastIndexOf('/')+1);if(!wanted.containsKey(base))continue;if(!entry.isFile()||entry.isSymbolicLink()||entry.isLink()||!seen.add(base))throw new IOException("Invalid model entry");extract(tar,new File(stage,base),entry.getSize(),wanted.get(base));}}
        }
        if(seen.size()!=wanted.size())throw new IOException("Missing model files");
    }
    private static void extract(InputStream in,File target,long size,JSONObject spec) throws Exception{
        if(size!=spec.getLong("size"))throw new IOException("Model file size mismatch");long written=0;try(FileOutputStream out=new FileOutputStream(target)){byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1){check();written+=n;if(written>size)throw new IOException("Model file too large");out.write(b,0,n);}out.getFD().sync();}if(written!=size||!sha(target).equals(spec.getString("sha256")))throw new IOException("Model checksum mismatch: "+target.getName());
    }
    static void erase(Context c,File file,File parent) throws IOException{
        String p=parent.getCanonicalPath()+File.separator,path=file.getCanonicalPath();if(!path.startsWith(p))throw new IOException("Unsafe model path");if(!file.exists())return;
        if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Cannot read folder");for(File child:children)erase(c,child,parent);}
        if(!file.delete())throw new IOException("Cannot delete "+file.getName());
    }
}
