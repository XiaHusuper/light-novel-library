package com.xiahu.reader;
import android.app.*;
import android.os.Bundle;
import android.content.*;
import org.json.*;
import java.util.*;
import java.io.IOException;
import java.security.KeyStore;
import java.util.concurrent.*;

public final class RecoveryTest extends Instrumentation {
 static final String STORE="reader_recovery_fixture",ALIAS="ReaderRecoveryFixture",USER="OriginalRecoveryUser",PASSWORD="OriginalRecoveryPassword!42";
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
 static final class Fake implements WenkuAccount.Transport{
  int logins,uid=123,writes;boolean expired,deny,offline;String token="";
  public ApiClient.Exchange call(String action,String cookie)throws Exception{
   if(offline)throw new IOException("Synthetic network unavailable");
   if(action.startsWith("action=login")){
    logins++;check(action.contains("username="+ApiClient.encoded(USER))&&action.contains("password="+ApiClient.encoded(PASSWORD)),"Restored credentials are used only for the account login transport");
    if(deny)return new ApiClient.Exchange("3",Collections.emptyList());
    expired=false;token="RecoveryFixtureToken"+logins;return new ApiClient.Exchange("1",Arrays.asList("PHPSESSID="+token+"; Secure; HttpOnly; Path=/"));
   }
   if(action.equals("action=userinfo"))return new ApiClient.Exchange("<metadata><item name=\"uname\">reader-fixture</item><item name=\"uid\">"+uid+"</item></metadata>",Collections.emptyList());
   if(expired||!cookie.contains(token))return new ApiClient.Exchange("4",Collections.emptyList());
   if(action.startsWith("action=bookcase&do=")){writes++;return new ApiClient.Exchange("1",Collections.emptyList());}
   return new ApiClient.Exchange("<metadata><book aid=\"129\"><name>Original fixture</name></book></metadata>",Collections.emptyList());
  }
 }
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){
  Context c=getTargetContext();Bundle out=new Bundle();boolean ok=false;
  try{
   c.getSharedPreferences(STORE,0).edit().clear().commit();Fake f=new Fake();WenkuAccount account=new WenkuAccount(c,f,STORE,ALIAS);
   account.login(USER,PASSWORD);check(f.logins==1,"Initial explicit login succeeds");
   String raw=c.getSharedPreferences(STORE,0).getAll().toString();check(!raw.contains(PASSWORD)&&!raw.contains(USER)&&!raw.contains(f.token),"Only encrypted sensitive state persists");
   check(!account.status().toString().contains(PASSWORD)&&!account.status().has("credentials"),"Credentials are absent from the UI response");
   WenkuAccount restored=new WenkuAccount(c,f,STORE,ALIAS);f.expired=true;JSONObject state=restored.pull();
   check(state.getBoolean("signedIn")&&f.logins==2&&state.getJSONArray("books").length()==1,"Expired restored session recovers and refreshes the same shelf");
   restored.pull();check(f.logins==2,"A valid session does not log in again");
   f.offline=true;boolean failed=false;try{restored.pull();}catch(Exception e){failed=true;}check(failed&&restored.status().getBoolean("signedIn")&&f.logins==2,"Network failure does not invalidate credentials or trigger login");f.offline=false;
   restored.login(USER,PASSWORD);int before=f.logins;f.expired=true;
   ExecutorService workers=Executors.newFixedThreadPool(2);final WenkuAccount concurrent=restored;
   try{Future<JSONObject> a=workers.submit(()->concurrent.pull()),b=workers.submit(()->concurrent.pull());check(a.get(10,TimeUnit.SECONDS).getBoolean("signedIn")&&b.get(10,TimeUnit.SECONDS).getBoolean("signedIn")&&f.logins==before+1,"Concurrent refreshes share one serialized recovery");}finally{workers.shutdownNow();}
   restored.login(USER,PASSWORD);before=f.logins;f.expired=true;f.deny=true;failed=false;try{restored.pull();}catch(Exception e){failed=true;}
   check(failed&&f.logins==before+1&&!restored.status().getBoolean("signedIn")&&restored.status().getJSONArray("books").length()==1,"Failed recovery requests manual login and retains shelf");
   try{restored.pull();}catch(Exception ignored){}check(f.logins==before+1,"Failed recovery is not retried in a loop");
   restored=new WenkuAccount(c,f,STORE,ALIAS);try{restored.pull();}catch(Exception ignored){}check(f.logins==before+1&&!restored.status().getBoolean("signedIn"),"Failure remains bounded after process restart");
   f.deny=false;restored.login(USER,PASSWORD);check(restored.status().getBoolean("signedIn"),"Explicit login replaces the blocked recovery state");
   f.uid=999;f.expired=true;before=f.logins;failed=false;try{restored.pull();}catch(Exception e){failed=true;}
   check(failed&&f.logins==before+1&&restored.status().getJSONObject("profile").getInt("uid")==123,"Recovery rejects an unexpected account without replacing the old snapshot");
   restored.logout();check(c.getSharedPreferences(STORE,0).getAll().isEmpty(),"Explicit logout clears both session and saved credentials");
   before=f.logins;WenkuAccount signedOut=new WenkuAccount(c,f,STORE,ALIAS);try{signedOut.pull();}catch(Exception ignored){}check(f.logins==before&&!signedOut.status().getBoolean("signedIn"),"Signed-out state never restores an account");
   c.getSharedPreferences(STORE,0).edit().putString("encrypted","invalid:ciphertext").commit();WenkuAccount broken=new WenkuAccount(c,f,STORE,ALIAS);check(!broken.status().getBoolean("signedIn"),"Unreadable secure storage fails closed");
   out.putString("report",new JSONObject().put("passed",true).put("encryptedCredentialRestore",true).put("noCredentialInUiOrPlaintext",true).put("backgroundRecovery",true).put("oneRecoveryForConcurrentRequests",true).put("boundedFailureAcrossRestart",true).put("networkFailureKeepsSession",true).put("accountIdentityChecked",true).put("logoutClearsCredentials",true).put("transport","isolated synthetic fixture").toString());ok=true;
  }catch(Throwable e){out.putString("failure",e.toString());}
  finally{c.getSharedPreferences(STORE,0).edit().clear().commit();try{KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);if(k.containsAlias(ALIAS))k.deleteEntry(ALIAS);}catch(Exception ignored){}}
  finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
 }
}
