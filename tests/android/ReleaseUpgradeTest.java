package com.xiahu.reader;
import android.app.*;import android.content.*;import android.content.pm.PackageManager;import android.os.*;import org.json.*;import java.io.*;import java.nio.file.Files;
/** Two phases on an isolated emulator, around an actual cover installation. No account or source access. */
public final class ReleaseUpgradeTest extends Instrumentation {
 private static final String STORE="reader_release_upgrade_fixture";
 private String phase;
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
 @Override public void onCreate(Bundle args){super.onCreate(args);phase=args.getString("phase","");start();}
 static JSONObject settled(AppUpdater updater)throws Exception{long end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){JSONObject status=updater.status();if(!java.util.Arrays.asList("checking","downloading","verifying").contains(status.getString("state")))return status;Thread.sleep(20);}throw new AssertionError("Local update timed out");}
 @Override public void onStart(){Bundle out=new Bundle();boolean ok=false;Context c=getTargetContext();AppUpdater updater=null;
  try{
   SharedPreferences proof=c.getSharedPreferences(STORE,0),state=c.getSharedPreferences("reader_state",0);File marker=new File(c.getFilesDir(),"release-upgrade-proof.bin");
   int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
   String signers=AppUpdater.signers(c.getPackageManager().getPackageInfo(c.getPackageName(),flags)).toString();
   if(phase.equals("before")){
    check(AppUpdater.installedCode(c)==28,"Latest delivered test version installed");
    File apk=new File("/data/local/tmp/reader-release-0.8.3.apk");
    final String metadata=AppUpdater.readText(new FileInputStream("/data/local/tmp/reader-release-update.json"),65536);
    AppUpdater.Release release=new AppUpdater.Release(new JSONObject(metadata));check(release.code==29&&release.version.equals("0.8.3"),"Formal release has higher update number");
    AppUpdater.Source local=new AppUpdater.Source(){public InputStream open(String url)throws Exception{if(url.equals(AppUpdater.MANIFEST_URL))return new ByteArrayInputStream(metadata.getBytes("UTF-8"));check(url.equals(release.url),"Only declared release APK requested");return new FileInputStream(apk);}};
    File folder=new File(c.getCacheDir(),"formal-upgrade-fixture");c.getSharedPreferences("reader_updates_"+folder.getName(),0).edit().clear().commit();
    updater=new AppUpdater(c,local,folder);updater.check();check(settled(updater).getString("state").equals("available"),"Test build detects formal update");check(updater.status().getBoolean("noticeAllowed"),"Test build can show formal update notice");
    updater.download();check(settled(updater).getString("state").equals("ready"),"Actual formal APK passes hash, package, version and same-signer checks");
    check(updater.installIntent().getData().getScheme().equals("content"),"System installer handoff supported");
    String fixture=new JSONObject().put("history",new JSONArray().put(new JSONObject().put("id","fixture_29").put("index",3))).put("shelf",new JSONArray().put(new JSONObject().put("id","fixture_29").put("favorite",true))).put("preference",new JSONObject().put("theme","paper")).toString();
    proof.edit().putBoolean("hadState",state.contains("state")).putString("originalState",state.getString("state","")).putString("expected",fixture).putString("signers",signers).commit();
    check(state.edit().putString("state",fixture).commit(),"Fixture history and shelf persisted");Files.write(marker.toPath(),"formal-upgrade-preserved".getBytes("UTF-8"));
    out.putString("report",new JSONObject().put("passed",true).put("phase",phase).put("fromCode",28).put("toCode",29).put("formalUpdateAvailable",true).put("actualApkVerifiedByTestClient",true).put("networkCalls",0).toString());
   }else if(phase.equals("after")){
    check(AppUpdater.installedCode(c)==29&&c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName.equals("0.8.3"),"Formal APK installed over test version");
    check(proof.contains("expected")&&proof.getString("expected","").equals(state.getString("state",null)),"History, shelf and settings remain byte-for-byte after cover install");
    check(new String(Files.readAllBytes(marker.toPath()),"UTF-8").equals("formal-upgrade-preserved"),"Internal application file retained");check(signers.equals(proof.getString("signers","")),"Signer unchanged");
    SharedPreferences.Editor restore=state.edit();if(proof.getBoolean("hadState",false))restore.putString("state",proof.getString("originalState",""));else restore.remove("state");check(restore.commit(),"Restore pre-test state");proof.edit().clear().commit();marker.delete();
    out.putString("report",new JSONObject().put("passed",true).put("phase",phase).put("installedCode",29).put("sameSigner",true).put("stateAndInternalFileRetained",true).put("networkCalls",0).toString());
   }else throw new AssertionError("Specify before or after phase");ok=true;
  }catch(Throwable e){out.putString("failure",e.toString());}finally{if(updater!=null)updater.close();}finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
 }
}
