package com.xiahu.reader;
import android.app.*;import android.content.Context;import android.os.Bundle;import org.json.*;import java.util.*;import java.security.KeyStore;
/** Isolated account fixtures: no real login, website mutation or content fetch. */
public final class ShelfRollbackTest extends Instrumentation {
 static final String STORE="reader_rollback_fixture",ALIAS="ReaderRollbackFixture";
 static class Fake extends ShelfChangeTest.Fake {int idReads;public ApiClient.Exchange call(String action,String cookie)throws Exception{if(action.equals("action=bookcase&do=list&t=0"))idReads++;return super.call(action,cookie);}}
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
 static boolean has(JSONObject status,int id)throws Exception{JSONArray a=status.getJSONArray("books");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getInt("id")==id)return true;return false;}
 static WenkuAccount fresh(Context c,Fake f)throws Exception{c.getSharedPreferences(STORE,0).edit().clear().commit();WenkuAccount a=new WenkuAccount(c,f,STORE,ALIAS);a.login("OriginalFixture","OriginalFixturePassword42");return a;}
 static void expireReceipt(WenkuAccount a,int id)throws Exception{java.lang.reflect.Field field=WenkuAccount.class.getDeclaredField("session");field.setAccessible(true);((JSONObject)field.get(a)).getJSONObject("pendingShelf").getJSONObject(String.valueOf(id)).put("at",System.currentTimeMillis()-31*60*1000);}
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Context c=getTargetContext();Bundle out=new Bundle();boolean ok=false;try{
  Fake f=new Fake();WenkuAccount a=fresh(c,f);a.change(129,false);JSONObject s=a.pull();check(!has(s,129)&&s.getInt("pendingShelfCount")==0,"Removal confirmed by one current snapshot");f.stale=true;s=a.pull();check(!has(s,129)&&s.getInt("pendingShelfCount")==1&&f.writes==1,"A later older snapshot cannot restore a confirmed removal");
  a=new WenkuAccount(c,f,STORE,ALIAS);check(!has(a.pull(),129),"Guard persists across process restart");a.login("OriginalFixture","OriginalFixturePassword42");check(!has(a.status(),129)&&a.status().getInt("pendingShelfCount")==1,"Same-account login preserves recent accepted action");
  f=new Fake();a=fresh(c,f);a.change(200,true);a.pull();f.stale=true;s=a.pull();check(has(s,200)&&s.getInt("pendingShelfCount")==1&&f.writes==1,"An older snapshot cannot erase a confirmed addition");
  f=new Fake();a=fresh(c,f);a.change(129,false);f.stale=true;f.expired=true;int before=f.idReads;s=a.pull();check(f.idReads-before==3&&!has(s,129)&&s.getInt("pendingShelfCount")==1,"Recovery retries a raw read, never confirms from local overlaid books");
  f=new Fake();a=fresh(c,f);a.change(200,true);a.pull();f.books.add(300);f.books.remove(129);s=a.pull();check(has(s,200)&&has(s,300)&&!has(s,129)&&f.writes==1,"Website-only changes apply when returned; only recent App target is guarded");
  f.books.remove(200);s=a.pull();check(has(s,200),"Opposite website edit of a recently modified book waits during bounded guard");expireReceipt(a,200);s=a.pull();check(!has(s,200)&&has(s,300)&&s.getInt("pendingShelfCount")==0&&f.writes==1,"Website membership eventually wins after guard expires, with no extra writes");
  f=new Fake();a=fresh(c,f);a.change(200,true);a.pull();expireReceipt(a,200);s=a.pull();check(has(s,200)&&s.getInt("pendingShelfCount")==0&&s.optString("warning").isEmpty(),"Matching expired receipt retires without a false warning");
  out.putString("report",new JSONObject().put("passed",true).put("confirmedThenOlderAddAndRemoveProtected",true).put("restartAndSameAccountLoginProtected",true).put("recoveryUsesActualRawRead",true).put("websiteChangesEventuallyApplied",true).put("noRepeatedWrites",true).put("guardMinutes",30).put("realAccountUsed",false).toString());ok=true;
 }catch(Throwable e){out.putString("failure",e.toString());}finally{c.getSharedPreferences(STORE,0).edit().clear().commit();try{KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);if(k.containsAlias(ALIAS))k.deleteEntry(ALIAS);}catch(Exception ignored){}}finish(ok?Activity.RESULT_OK:Activity.RESULT_CANCELED,out);}
}
