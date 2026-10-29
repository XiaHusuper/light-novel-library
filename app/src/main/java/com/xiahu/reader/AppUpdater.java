package com.xiahu.reader;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Public release metadata and user-initiated updates, separate from account and book storage. */
final class AppUpdater implements Closeable {
    static final Object FILE_LOCK=new Object();
    static final long MAX_APK=128L*1024*1024;
    static final String MANIFEST_NAME="LightNovelLibrary-update.json";
    static final String MANIFEST_URL=ProjectInfo.BASE+"/releases/latest/download/"+MANIFEST_NAME;
    static final String MIME="application/vnd.android.package-archive";
    interface Source extends Closeable {InputStream open(String url)throws Exception;default void close(){}}
    static final class HttpSource implements Source {
        private volatile HttpURLConnection active;
        static void validate(URL url)throws IOException {
            String host=url.getHost().toLowerCase(Locale.ROOT);
            if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getPort()!=-1&&url.getPort()!=443||
               !Arrays.asList("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com").contains(host))
                throw new IOException("更新下载地址无效");
        }
        public InputStream open(String address)throws Exception {
            URL url=new URL(address);
            for(int hop=0;hop<6;hop++){
                validate(url);HttpURLConnection c=(HttpURLConnection)url.openConnection();active=c;
                c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent","LightNovelLibrary-Android");c.setRequestProperty("Accept-Encoding","identity");
                c.setRequestProperty("Cache-Control","no-cache");c.setRequestProperty("Cookie","");
                int status=c.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){String location=c.getHeaderField("Location");c.disconnect();if(location==null)throw new IOException("更新地址未返回下载位置");url=new URL(url,location);continue;}
                if(status!=200){c.disconnect();throw new IOException(status==404?"暂未找到更新信息，请稍后再试":status==403||status==429?"更新服务暂时无法访问，请稍后重试":"更新服务响应异常，请稍后重试");}
                final HttpURLConnection connection=c;
                return new FilterInputStream(c.getInputStream()){@Override public void close()throws IOException{try{super.close();}finally{connection.disconnect();if(active==connection)active=null;}}};
            }
            throw new IOException("更新地址跳转次数过多");
        }
        public void close(){HttpURLConnection c=active;if(c!=null)c.disconnect();}
    }
    static final class Release {
        final String version,url,sha,notes;final int code,minSdk;final long size;
        Release(JSONObject value)throws Exception {
            version=value.getString("version");code=value.getInt("versionCode");minSdk=value.optInt("minSdk",26);
            JSONObject apk=value.getJSONObject("apk");url=apk.getString("url");sha=apk.getString("sha256").toLowerCase(Locale.ROOT);size=apk.getLong("size");notes=value.optString("notes");
            if(!version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")||code<=0||minSdk<26||minSdk>100||size<=0||size>MAX_APK||!sha.matches("[a-f0-9]{64}")||notes.length()>16000)
                throw new IOException("更新信息格式无效");
            String expected=ProjectInfo.BASE+"/releases/download/v"+version+"/LightNovelLibrary-"+version+".apk";
            if(!url.equals(expected))throw new IOException("更新包来源不匹配");
        }
        JSONObject json()throws Exception{return new JSONObject().put("version",version).put("versionCode",code).put("minSdk",minSdk).put("notes",notes).put("apk",new JSONObject().put("url",url).put("sha256",sha).put("size",size));}
    }
    private static AppUpdater shared;
    static synchronized AppUpdater get(Context context)throws Exception{if(shared==null)shared=new AppUpdater(context,new HttpSource(),new File(context.getFilesDir(),"updates"));return shared;}
    private final Context context;private final SharedPreferences preferences;private final Source source;private final File folder,part,apk,record;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String phase="idle",error="",message="";private Release release;private long bytes,generation;
    private volatile InputStream activeStream;
    AppUpdater(Context context,Source source,File folder)throws Exception {
        this.context=context.getApplicationContext();this.source=source;this.folder=folder.getCanonicalFile();preferences=context.getSharedPreferences("reader_updates_"+this.folder.getName(),Context.MODE_PRIVATE);
        if(!this.folder.exists()&&!this.folder.mkdirs())throw new IOException("无法准备更新存储空间");
        part=new File(this.folder,"download.part");apk=new File(this.folder,"verified.apk");record=new File(this.folder,"verified.json");
        synchronized(FILE_LOCK){Files.deleteIfExists(part.toPath());Files.deleteIfExists(new File(this.folder,"verified.tmp").toPath());if(record.isFile())try{Release saved=new Release(new JSONObject(readText(new FileInputStream(record),65536)));if(saved.code<=installedCode(context)){Files.deleteIfExists(apk.toPath());Files.deleteIfExists(record.toPath());}}catch(Exception ignored){Files.deleteIfExists(apk.toPath());Files.deleteIfExists(record.toPath());}}
        try{Release saved=new Release(new JSONObject(preferences.getString("release","{}")));release=saved;phase=saved.code<=installedCode(context)?"current":saved.minSdk>Build.VERSION.SDK_INT?"unsupported":"available";message=phase.equals("current")?"已经是最新版本":"发现新版本";if(phase.equals("available")&&apk.isFile()&&record.isFile())synchronized(FILE_LOCK){Release stored=new Release(new JSONObject(readText(new FileInputStream(record),65536)));if(stored.sha.equals(saved.sha)){verify(apk,saved,context);phase="ready";bytes=saved.size;message="更新包已准备好";}}}catch(Exception ignored){}
    }
    static long installedCode(Context c)throws Exception{return packageCode(c.getPackageManager().getPackageInfo(c.getPackageName(),0));}
    static long packageCode(PackageInfo p){return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;}
    static String readText(InputStream input,int max)throws Exception{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("更新信息过大");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private boolean busy(){return phase.equals("checking")||phase.equals("downloading")||phase.equals("verifying");}
    synchronized JSONObject status()throws Exception {
        return new JSONObject().put("state",phase).put("error",error).put("message",message).put("version",context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName)
            .put("latestVersion",release==null?"":release.version).put("notes",release==null?"":release.notes).put("bytes",bytes).put("total",release==null?0:release.size)
            .put("progress",release==null?0:Math.min(100,bytes*100/release.size)).put("installAllowed",context.getPackageManager().canRequestPackageInstalls()).put("noticeAllowed",release!=null&&release.code>installedCode(context)&&(preferences.getInt("deferredCode",0)!=release.code||System.currentTimeMillis()-preferences.getLong("deferredAt",0)>24*60*60*1000L));
    }
    synchronized JSONObject autoCheck()throws Exception {
        long now=System.currentTimeMillis();
        if(busy()||now-preferences.getLong("checkedAt",0)<60*1000L||now-preferences.getLong("autoAttemptAt",0)<60*1000L)return status();
        preferences.edit().putLong("autoAttemptAt",now).apply();return check();
    }
    synchronized JSONObject defer()throws Exception{if(release!=null)preferences.edit().putInt("deferredCode",release.code).putLong("deferredAt",System.currentTimeMillis()).apply();return status();}
    synchronized JSONObject check()throws Exception {
        if(busy())return status();long token=++generation;phase="checking";error="";message="正在检查新版本…";
        worker.execute(()->{
            try{
                Release next=new Release(new JSONObject(readText(source.open(MANIFEST_URL),65536)));
                if(!valid(token))return;
                if(next.code<=installedCode(context)){synchronized(FILE_LOCK){Files.deleteIfExists(apk.toPath());Files.deleteIfExists(record.toPath());}finish(token,"current",next,"已经是最新版本");return;}
                if(next.minSdk>Build.VERSION.SDK_INT){finish(token,"unsupported",next,"新版本需要更新的 Android 系统，暂时无法安装");return;}
                boolean ready=false;synchronized(FILE_LOCK){if(apk.isFile()&&record.isFile())try{Release saved=new Release(new JSONObject(readText(new FileInputStream(record),65536)));if(saved.sha.equals(next.sha)){verify(apk,next,context);ready=true;}}catch(Exception ignored){}}
                finish(token,ready?"ready":"available",next,ready?"安装包已准备好":"发现新版本");
            }catch(Exception e){fail(token,e);}
        });return status();
    }
    synchronized JSONObject download()throws Exception {
        if(busy())return status();if(release==null||release.code<=installedCode(context)||release.minSdk>Build.VERSION.SDK_INT)throw new IOException("请先检查可用的新版本");
        final Release target=release;long token=++generation;phase="downloading";bytes=0;error="";message="正在下载安装包…";
        worker.execute(()->{
            try{
                synchronized(FILE_LOCK){if(apk.isFile()&&record.isFile())try{verify(apk,target,context);finish(token,"ready",target,"更新包已准备好");return;}catch(Exception ignored){}Files.deleteIfExists(part.toPath());}
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
                try(InputStream in=source.open(target.url);FileOutputStream out=new FileOutputStream(part)){
                    activeStream=in;byte[] buffer=new byte[32768];int n;
                    while((n=in.read(buffer))!=-1){if(!valid(token))throw new InterruptedIOException("下载已停止");count+=n;if(count>target.size||count>MAX_APK)throw new IOException("更新包大小不匹配");out.write(buffer,0,n);digest.update(buffer,0,n);synchronized(this){if(token==generation)bytes=count;}}
                    out.getFD().sync();
                }finally{activeStream=null;}
                if(count!=target.size||!hex(digest.digest()).equals(target.sha))throw new IOException("安装包校验失败，请重新下载");
                synchronized(this){if(!valid(token))return;phase="verifying";message="正在核对安装包…";}
                verify(part,target,context);if(!valid(token))return;
                synchronized(FILE_LOCK){Files.move(part.toPath(),apk.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);File temp=new File(folder,"verified.tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(target.json().toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),record.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                finish(token,"ready",target,"下载完成，可以安装");
            }catch(Exception e){fail(token,e);}finally{synchronized(FILE_LOCK){try{Files.deleteIfExists(part.toPath());}catch(Exception ignored){}}}
        });return status();
    }
    synchronized JSONObject cancel()throws Exception {
        if(!busy())return status();generation++;phase=release==null?"idle":"available";error="";message="已停止下载";bytes=0;
        source.close();InputStream stream=activeStream;if(stream!=null)try{stream.close();}catch(Exception ignored){}return status();
    }
    private synchronized boolean valid(long token){return generation==token;}
    private synchronized void finish(long token,String value,Release r,String text){if(token!=generation)return;release=r;phase=value;error="";message=text;if(r!=null)try{preferences.edit().putString("release",r.json().toString()).putLong("checkedAt",System.currentTimeMillis()).apply();}catch(Exception ignored){}if(value.equals("ready"))bytes=r.size;}
    private synchronized void fail(long token,Exception failure){if(token!=generation)return;phase="error";error=failure instanceof IOException?failure.getMessage():"更新暂未完成，请重试";message="";}
    Intent installIntent()throws Exception {
        Release target;synchronized(this){if(!phase.equals("ready")||release==null)throw new IOException("请先下载更新包");target=release;}
        try{synchronized(FILE_LOCK){verify(apk,target,context);Uri uri=UpdateFileProvider.uri(context,target.sha);Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("应用更新",uri));return intent;}}
        catch(Exception e){synchronized(this){phase="error";error="安装包已失效，请重新检查并下载";}throw new IOException("安装包已失效，请重新检查并下载");}
    }
    synchronized void markInstallPending()throws Exception{if(release==null||!phase.equals("ready"))throw new IOException("请先下载更新包");if(!preferences.edit().putString("pendingInstallSha",release.sha).putLong("pendingInstallAt",System.currentTimeMillis()).commit())throw new IOException("无法保存安装请求，请重试");}
    synchronized boolean pendingInstall(){String sha=preferences.getString("pendingInstallSha","");boolean valid=!sha.isEmpty()&&release!=null&&phase.equals("ready")&&sha.equals(release.sha)&&System.currentTimeMillis()-preferences.getLong("pendingInstallAt",0)<15*60*1000L;if(!valid&&!sha.isEmpty())preferences.edit().remove("pendingInstallSha").remove("pendingInstallAt").apply();return valid;}
    synchronized void clearInstallPending(){preferences.edit().remove("pendingInstallSha").remove("pendingInstallAt").commit();}
    static Intent permissionIntent(Context c){return new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+c.getPackageName()));}
    static String hex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    static String hash(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
    static Set<String> signers(PackageInfo p)throws Exception {
        android.content.pm.Signature[] values=Build.VERSION.SDK_INT>=28&&p.signingInfo!=null?p.signingInfo.getApkContentsSigners():p.signatures;
        Set<String> result=new HashSet<>();if(values!=null)for(android.content.pm.Signature v:values)result.add(hex(MessageDigest.getInstance("SHA-256").digest(v.toByteArray())));return result;
    }
    static void verify(File file,Release release,Context context)throws Exception {
        if(!file.isFile()||file.length()!=release.size||!hash(file).equals(release.sha))throw new IOException("安装包校验失败，请重新下载");
        PackageManager pm=context.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo incoming=pm.getPackageArchiveInfo(file.getAbsolutePath(),flags),installed=pm.getPackageInfo(context.getPackageName(),flags);
        if(incoming==null||!context.getPackageName().equals(incoming.packageName)||packageCode(incoming)!=release.code||packageCode(incoming)<=packageCode(installed)||!release.version.equals(incoming.versionName)||incoming.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)
            throw new IOException("安装包版本或包名不匹配");
        Set<String> expected=signers(installed);if(expected.isEmpty()||!expected.equals(signers(incoming)))throw new IOException("安装包签名不匹配");
    }
    public void close(){try{cancel();}catch(Exception ignored){}worker.shutdownNow();source.close();}
}
