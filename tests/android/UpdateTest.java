package com.xiahu.reader;
import android.app.*;import android.content.*;import android.content.pm.*;import android.database.Cursor;import android.net.Uri;import android.os.*;import org.json.*;
import java.io.*;import java.net.URL;import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;

public final class UpdateTest extends Instrumentation {
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
 static JSONObject manifest(File file,int code,String version)throws Exception{return new JSONObject().put("version",version).put("versionCode",code).put("minSdk",26).put("notes","Original update fixture").put("apk",new JSONObject().put("url",ProjectInfo.BASE+"/releases/download/v"+version+"/LightNovelLibrary-"+version+".apk").put("size",file.length()).put("sha256",AppUpdater.hash(file)));}
 static final class Fake implements AppUpdater.Source {
  JSONObject value;File file;int delay;AtomicInteger manifests=new AtomicInteger(),downloads=new AtomicInteger();
  Fake(File file)throws Exception{this.file=file;value=manifest(file,16,"0.8.2");}
  public InputStream open(String url)throws Exception {
   if(url.equals(AppUpdater.MANIFEST_URL)){manifests.incrementAndGet();return new ByteArrayInputStream(value.toString().getBytes("UTF-8"));}
   check(url.equals(value.getJSONObject("apk").getString("url")),"Download uses only native release URL");downloads.incrementAndGet();
   return new FilterInputStream(new FileInputStream(file)){@Override public int read(byte[] b,int off,int n)throws IOException{if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException e){throw new InterruptedIOException();}return super.read(b,off,n);}};
  }
 }
 static JSONObject settled(AppUpdater manager)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){JSONObject state=manager.status();if(!Arrays.asList("checking","downloading","verifying").contains(state.getString("state")))return state;Thread.sleep(20);}throw new AssertionError("Update job timed out");}
 static void reject(JSONObject value,String name)throws Exception{boolean rejected=false;try{new AppUpdater.Release(value);}catch(Exception e){rejected=true;}check(rejected,name);}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){
  Context c=getTargetContext();Bundle out=new Bundle();boolean ok=false;List<AppUpdater> managers=new ArrayList<>();List<File> folders=new ArrayList<>();File production=new File(c.getFilesDir(),"updates");boolean providerFixture=false;
  try{
   File inputs=new File(c.getCacheDir(),"update-inputs"),next=new File(inputs,"next.apk"),wrong=new File(inputs,"wrong-signature.apk");
   check(next.isFile()&&wrong.isFile(),"Local-only fixtures provided");JSONObject valid=manifest(next,16,"0.8.2");
   JSONObject bad=new JSONObject(valid.toString());bad.getJSONObject("apk").put("url","http://example.invalid/app.apk");reject(bad,"External or insecure APK URL refused");
   bad=new JSONObject(valid.toString());bad.getJSONObject("apk").put("sha256","not-a-digest");reject(bad,"Missing digest refused");
   bad=new JSONObject(valid.toString());bad.getJSONObject("apk").put("size",AppUpdater.MAX_APK+1);reject(bad,"Oversized APK refused");
   bad=new JSONObject(valid.toString());bad.put("version","../evil");reject(bad,"Invalid version refused");
   for(String url:new String[]{"http://github.com/file","https://github.com.example.invalid/file","https://github.com:444/file","https://user@github.com/file"}){boolean rejected=false;try{AppUpdater.HttpSource.validate(new URL(url));}catch(Exception e){rejected=true;}check(rejected,"Unsafe redirect refused");}
   AppUpdater.HttpSource.validate(new URL("https://release-assets.githubusercontent.com/file"));
   File folder=new File(c.getCacheDir(),"updater-good-fixture");folders.add(folder);Fake source=new Fake(next);AppUpdater good=new AppUpdater(c,source,folder);managers.add(good);
   good.check();good.check();check(settled(good).getString("state").equals("available")&&source.manifests.get()==1,"Concurrent checks coalesce");
   source.delay=2;good.download();good.download();check(settled(good).getString("state").equals("ready")&&source.downloads.get()==1,"Single verified download");
   good.download();check(settled(good).getString("state").equals("ready")&&source.downloads.get()==1,"Verified APK reused");
   good.markInstallPending();AppUpdater restored=new AppUpdater(c,new Fake(next),folder);managers.add(restored);check(restored.pendingInstall()&&restored.status().getString("state").equals("ready"),"Permission request and verified APK survive process recreation");restored.clearInstallPending();check(!restored.pendingInstall(),"Pending request consumed after installer handoff");
   Intent install=good.installIntent();check(install.getAction().equals(Intent.ACTION_VIEW)&&install.getType().equals(AppUpdater.MIME)&&install.getData().getScheme().equals("content"),"System installer gets content URI");
   check((install.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0&&(install.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0,"Installer receives read-only grant");
   check(AppUpdater.permissionIntent(c).getDataString().equals("package:"+c.getPackageName()),"Permission screen targets only this app");
   check(good.status().getBoolean("noticeAllowed"),"New update can be offered");good.defer();check(!good.status().getBoolean("noticeAllowed"),"Later defers same version");
   int requests=source.manifests.get();good.autoCheck();check(source.manifests.get()==requests,"Recent check reused on startup");
   for(String kind:new String[]{"hash","signature","package","old","unsupported","cancel"}){
    File f=new File(c.getCacheDir(),"updater-"+kind+"-fixture");folders.add(f);Fake fake=new Fake(kind.equals("signature")?wrong:kind.equals("package")?new File(getContext().getPackageCodePath()):kind.equals("old")?new File(inputs,"old.apk"):next);
    if(kind.equals("hash"))fake.value.getJSONObject("apk").put("sha256",String.join("",Collections.nCopies(64,"0")));
    if(kind.equals("old"))fake.value=manifest(fake.file,14,"0.8.0");
    if(kind.equals("unsupported"))fake.value.put("minSdk",100);
    c.getSharedPreferences("reader_updates_"+f.getName(),0).edit().clear().commit();
    AppUpdater manager=new AppUpdater(c,fake,f);managers.add(manager);manager.check();JSONObject state=settled(manager);
    if(kind.equals("old")){check(state.getString("state").equals("current"),"Older release cannot downgrade");continue;}
    if(kind.equals("unsupported")){check(state.getString("state").equals("unsupported"),"Unsupported Android version blocked");continue;}
    if(kind.equals("cancel")){
     fake.delay=10;manager.download();long end=System.currentTimeMillis()+5000;while(manager.status().getLong("bytes")==0&&System.currentTimeMillis()<end)Thread.sleep(10);manager.cancel();check(manager.status().getString("state").equals("available"),"Download cancel returns to available");Thread.sleep(200);check(!new File(f,"verified.apk").exists()&&!new File(f,"download.part").exists(),"Cancelled partial removed");continue;
    }
    manager.download();state=settled(manager);check(state.getString("state").equals("error")&&!new File(f,"verified.apk").exists(),"Wrong "+kind+" cannot reach installation");
   }
   check(!new File(production,"verified.apk").exists()&&!new File(production,"verified.json").exists(),"Provider integration uses no pre-existing download");production.mkdirs();providerFixture=true;
   Files.copy(new File(folder,"verified.apk").toPath(),new File(production,"verified.apk").toPath(),StandardCopyOption.REPLACE_EXISTING);Files.copy(new File(folder,"verified.json").toPath(),new File(production,"verified.json").toPath(),StandardCopyOption.REPLACE_EXISTING);
   Uri uri=install.getData();try(ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"r")){check(fd!=null&&fd.getStatSize()==next.length(),"Provider serves verified APK");}
   try(Cursor row=c.getContentResolver().query(uri,null,null,null,null)){check(row!=null&&row.moveToFirst()&&row.getLong(row.getColumnIndex("_size"))==next.length(),"Provider reports exact APK size");}
   for(String mode:new String[]{"w","rw"}){boolean refused=false;try{c.getContentResolver().openFileDescriptor(uri,mode);}catch(Exception e){refused=true;}check(refused,"Provider denies writes");}
   boolean refused=false;try{c.getContentResolver().openFileDescriptor(Uri.parse("content://"+c.getPackageName()+".updates/apk/../reader_state.xml"),"r");}catch(Exception e){refused=true;}check(refused,"Provider refuses unrelated files");
   ProviderInfo provider=c.getPackageManager().getProviderInfo(new ComponentName(c,UpdateFileProvider.class),0);check(!provider.exported&&provider.grantUriPermissions,"Provider is private and grants explicit URIs");
   out.putString("report",new JSONObject().put("passed",true).put("publicManifestAndRedirectValidation",true).put("nativeVersionAndSignatureVerification",true).put("wrongHashPackageAndSignerRejected",true).put("noDowngrade",true).put("cancelRemovesPartial",true).put("cachedApkReused",true).put("systemInstallerReadOnlyUri",true).put("noLibraryFilesExposed",true).put("startupCheckAndDeferral",true).put("installPermissionSurvivesProcessRecreation",true).put("network","isolated local APK fixtures only").toString());ok=true;
  }catch(Throwable e){out.putString("failure",e.toString());}
  finally{
   for(AppUpdater m:managers)m.close();
   for(File folder:folders){c.getSharedPreferences("reader_updates_"+folder.getName(),0).edit().clear().commit();for(String name:new String[]{"download.part","verified.apk","verified.json","verified.tmp"})new File(folder,name).delete();}
   if(providerFixture)for(String name:new String[]{"verified.apk","verified.json"})new File(production,name).delete();
  }
  finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
 }
}
