package com.xiahu.reader;
import android.app.*;
import android.os.Bundle;
import android.content.Context;
import org.json.*;
import java.util.*;
import java.io.IOException;
import java.security.KeyStore;

/** Isolated acknowledged-write, stale-snapshot and account-boundary regressions. */
public final class ShelfChangeTest extends Instrumentation {
 static final String STORE="reader_shelf_change_fixture",ALIAS="ReaderShelfChangeFixture";
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
 static final class Fake implements WenkuAccount.Transport {
  final Set<Integer> books=new LinkedHashSet<>(Arrays.asList(129));Set<Integer> beforeWrite=new LinkedHashSet<>(books);
  int logins,writes,reads,uid=123;String token="",mutationCode="1";
  boolean expired,expireWrite,deny,ambiguous,noApply,offlineRead,stale;
  String shelf(boolean idsOnly){StringBuilder s=new StringBuilder("<metadata>");for(int id:stale?beforeWrite:books){s.append("<book aid=\"").append(id).append("\">");if(!idsOnly)s.append("<name>Original story ").append(id).append("</name>");s.append("</book>");}return s.append("</metadata>").toString();}
  public ApiClient.Exchange call(String action,String cookie)throws Exception{
   if(action.startsWith("action=login")){logins++;if(deny)return response("3");expired=false;token="ShelfFixture"+logins;return new ApiClient.Exchange("1",Arrays.asList("PHPSESSID="+token+"; Secure; HttpOnly; Path=/"));}
   if(expired||!cookie.contains(token))return response("4");
   if(action.equals("action=userinfo"))return response("<metadata><item name=\"uname\">OriginalFixture</item><item name=\"uid\">"+uid+"</item></metadata>");
   if(action.startsWith("action=bookcase&do=add")||action.startsWith("action=bookcase&do=del")){
    writes++;beforeWrite=new LinkedHashSet<>(books);int id=Integer.parseInt(action.substring(action.lastIndexOf('=')+1));
    if(expireWrite){expireWrite=false;expired=true;return response("4");}
    if(!noApply&&mutationCode.equals("1")){if(action.contains("do=add"))books.add(id);else books.remove(id);}
    if(ambiguous)throw new IOException("Synthetic lost response");return response(mutationCode);
   }
   reads++;if(offlineRead)throw new IOException("Synthetic read failure");return response(shelf(action.contains("do=list")));
  }
  ApiClient.Exchange response(String body){return new ApiClient.Exchange(body,Collections.emptyList());}
 }
 WenkuAccount fresh(Context c,Fake fake)throws Exception{c.getSharedPreferences(STORE,0).edit().clear().commit();WenkuAccount a=new WenkuAccount(c,fake,STORE,ALIAS);a.login("OriginalFixture","OriginalFixturePassword42");return a;}
 boolean rejected(WenkuAccount a,int id,boolean add){try{a.change(id,add);return false;}catch(Exception expected){return true;}}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Context c=getTargetContext();Bundle out=new Bundle();boolean ok=false;
  try{
   Fake f=new Fake();WenkuAccount a=fresh(c,f);f.expired=true;a=new WenkuAccount(c,f,STORE,ALIAS);JSONObject s=a.change(200,true);check(s.getJSONArray("books").length()==2&&f.logins==2&&f.writes==1,"Restored expired session is checked before the first write");s=a.pull();check(s.getInt("pendingShelfCount")==0,"Matching server list clears acknowledged intent");
   f=new Fake();a=fresh(c,f);f.expireWrite=true;s=a.change(200,true);check(s.getJSONArray("books").length()==2&&f.logins==2&&f.writes==2,"Definite rejection permits exactly one recovered write");
   f=new Fake();a=fresh(c,f);f.stale=true;int reads=f.reads;s=a.change(200,true);check(s.getJSONArray("books").length()==2&&f.reads==reads&&f.writes==1,"Acknowledged add returns immediately without waiting on a stale listing");s=a.pull();check(s.getJSONArray("books").length()==2&&s.getInt("pendingShelfCount")==1&&f.writes==1,"Stale full and ID listings cannot undo acknowledged add");
   a=new WenkuAccount(c,f,STORE,ALIAS);s=a.pull();check(s.getJSONArray("books").length()==2&&s.getInt("pendingShelfCount")==1,"Acknowledged intent survives process recreation");s=a.change(200,false);check(s.getJSONArray("books").length()==1&&f.writes==2,"Acknowledged remove applies immediately");s=a.pull();check(s.getJSONArray("books").length()==1&&s.getInt("pendingShelfCount")==1,"Old listing cannot restore a removed book");f.stale=false;s=a.pull();check(s.getJSONArray("books").length()==1&&s.getInt("pendingShelfCount")==0,"Current list reconciles the latest add/remove intent");
   f=new Fake();a=fresh(c,f);a.change(200,true);f.expired=true;s=a.pull();check(s.getJSONArray("books").length()==2&&f.logins==2&&f.writes==1,"Recovery preserves acknowledged intent without replaying a write");
   f=new Fake();a=fresh(c,f);f.mutationCode="5";s=a.change(200,true);check(s.getJSONArray("books").length()==2,"Already-in-shelf acknowledgement is applied");f.mutationCode="7";s=a.change(129,false);check(s.getJSONArray("books").length()==1,"Already-absent acknowledgement is applied");
   f=new Fake();a=fresh(c,f);f.stale=true;a.change(200,true);java.lang.reflect.Field field=WenkuAccount.class.getDeclaredField("session");field.setAccessible(true);JSONObject session=(JSONObject)field.get(a);session.getJSONObject("pendingShelf").getJSONObject("200").put("at",System.currentTimeMillis()-31*60*1000);s=a.pull();check(s.getJSONArray("books").length()==1&&s.getInt("pendingShelfCount")==0&&!s.optString("warning").isEmpty(),"Persistently divergent intent expires with a visible warning instead of masking remote state forever");
   f=new Fake();a=fresh(c,f);f.ambiguous=true;check(rejected(a,200,true)&&f.writes==1&&a.status().getJSONArray("books").length()==1&&a.status().getInt("pendingShelfCount")==0,"Lost write response is never repeated or treated as an acknowledgement");
   f=new Fake();a=fresh(c,f);f.mutationCode="6";check(rejected(a,200,true)&&a.status().getInt("pendingShelfCount")==0,"Full-shelf rejection never fabricates membership");
   f=new Fake();a=fresh(c,f);f.offlineRead=true;check(a.change(200,true).getJSONArray("books").length()==2,"Read endpoint outage does not invalidate an acknowledged write");boolean failed=false;try{a.pull();}catch(Exception e){failed=true;}check(failed&&a.status().getJSONArray("books").length()==2&&f.writes==1,"Failed background reconciliation preserves accepted state and never repeats write");
   f=new Fake();a=fresh(c,f);a.change(200,true);f.uid=999;check(rejected(a,201,true)&&f.writes==1&&a.status().getJSONObject("profile").getInt("uid")==123,"Unexpected account is rejected before any new write");f.expired=false;a.login("OriginalFixture","OriginalFixturePassword42");check(a.status().getJSONObject("profile").getInt("uid")==999&&a.status().getInt("pendingShelfCount")==0,"Different account never inherits pending intents");
   f=new Fake();a=fresh(c,f);f.expired=true;f.deny=true;check(rejected(a,200,true)&&f.writes==0&&f.logins==2&&!a.status().getBoolean("signedIn"),"Failed recovery requests login and never sends a mutation");
   out.putString("report",new JSONObject().put("passed",true).put("acknowledgedAddRemoveImmediate",true).put("staleFullAndIdListsCannotRevert",true).put("pendingSurvivesRestartAndRecovery",true).put("latestIntentWins",true).put("pendingClearsWhenRemoteMatches",true).put("boundedDivergenceWithWarning",true).put("noDuplicateAmbiguousWrites",true).put("identityCheckedBeforeMutation",true).put("differentAccountNeverInheritsIntent",true).put("transport","isolated synthetic fixture; no real account used").toString());ok=true;
  }catch(Throwable e){out.putString("failure",e.toString());}
  finally{c.getSharedPreferences(STORE,0).edit().clear().commit();try{KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);if(k.containsAlias(ALIAS))k.deleteEntry(ALIAS);}catch(Exception ignored){}}
  finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);
 }
}
