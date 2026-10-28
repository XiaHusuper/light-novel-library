package com.xiahu.reader;
import android.app.*;
import android.os.Bundle;
import android.content.*;
import org.json.*;
import java.util.*;
import java.security.KeyStore;

public final class AccountTest extends Instrumentation {
    static final String STORE="reader_test_account_only",ALIAS="ReaderTestAccountOnly",TOKEN="SyntheticCookieValue123";
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    static final class Fake implements WenkuAccount.Transport {
        Set<Integer> books=new LinkedHashSet<>(Arrays.asList(129));int uid=101;String mode="ok";int writes;String loginCode="1",mutationCode="1";boolean blockApiWrite;
        public ApiClient.Exchange call(String action,String cookie)throws Exception{
            if(action.startsWith("action=login"))return new ApiClient.Exchange(loginCode,Arrays.asList("PHPSESSID="+TOKEN+"; HttpOnly; Secure; Path=/"));
            check(cookie.contains(TOKEN),"Cookie forwarded only to account transport");
            if(action.equals("action=userinfo"))return new ApiClient.Exchange("<metadata><item name=\"uname\">synthetic-reader</item><item name=\"nickname\">Reader</item><item name=\"uid\">"+uid+"</item></metadata>",Collections.emptyList());
            if(action.startsWith("action=bookcase&do=")){int aid=Integer.parseInt(action.substring(action.lastIndexOf('=')+1));writes++;if(!blockApiWrite){if(action.contains("do=add"))books.add(aid);else books.remove(aid);}return new ApiClient.Exchange(mutationCode,Collections.emptyList());}
            if(mode.equals("expired"))return new ApiClient.Exchange("4",Collections.emptyList());
            if(mode.equals("html"))return new ApiClient.Exchange("<html><body>Unavailable</body></html>",Collections.emptyList());
            if(mode.equals("other"))return new ApiClient.Exchange("<metadata><error>Unavailable</error></metadata>",Collections.emptyList());
            StringBuilder xml=new StringBuilder("<metadata>");for(int aid:books)xml.append("<book aid=\"").append(aid).append("\" date=\"2026-10-28\"><name>Original ").append(aid).append("</name></book>");return new ApiClient.Exchange(xml.append("</metadata>").toString(),Collections.emptyList());
        }
    }
    static final class WebFake implements WenkuAccount.Website {
        final Map<Integer,JSONObject> books=new LinkedHashMap<>();int apiCalls,uid=401;boolean incomplete,blockWrite,challenge;String lastMutation="";int restores;
        WebFake()throws Exception{books.put(129,book(129,70001));books.put(200,book(200,70002));}
        JSONObject book(int aid,int row)throws Exception{return new JSONObject().put("id",String.valueOf(aid)).put("title","Original "+aid).put("source","online").put("rowId",String.valueOf(row)).put("favorite",true);}
        public JSONObject page(String path,String kind,JSONObject expected)throws Exception{
            if(challenge)throw new WenkuAccount.WebsiteException("verification","Synthetic challenge");
            if(kind.equals("profile"))return new JSONObject().put("profile",new JSONObject().put("uid",String.valueOf(uid)).put("uname","web-reader"));
            if(kind.equals("action")){lastMutation=path;android.net.Uri u=android.net.Uri.parse(path);check(u.getQueryParameter("cid")==null,"No bookmark or chapter writes are permitted");if(!blockWrite){if(path.contains("delid=")){String row=u.getQueryParameter("delid");Integer removed=null;for(int id:books.keySet())if(books.get(id).getString("rowId").equals(row))removed=id;if(removed!=null)books.remove(removed);}else{int aid=Integer.parseInt(u.getQueryParameter("bid"));JSONObject b=books.get(aid);if(b==null){b=book(aid,70000+aid);books.put(aid,b);}}}return new JSONObject().put("received",true);}
            JSONArray items=new JSONArray();boolean groupOne=path.contains("classid=1");for(JSONObject book:books.values())if((book.getInt("id")==200)==groupOne)items.put(new JSONObject(book.toString()));int count=items.length();if(incomplete&&!groupOne)items=new JSONArray();
            return new JSONObject().put("books",items).put("total",books.size()).put("inGroup",count).put("pages",1).put("groups",new JSONArray().put(0).put(1));
        }
        public String cookie(){return "PHPSESSID=WebsiteSyntheticOnly";}
        public void restore(String value){restores++;}
        public void clear(){}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();Context context=getTargetContext();
        try{
            context.getSharedPreferences(STORE,0).edit().clear().commit();KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);if(keys.containsAlias(ALIAS))keys.deleteEntry(ALIAS);
            Fake fake=new Fake();WenkuAccount account=new WenkuAccount(context,fake,STORE,ALIAS);check(!account.status().getBoolean("signedIn"),"Initial signed-out state");
            JSONObject status=account.login("synthetic-reader","SyntheticTestPassword");check(status.getBoolean("signedIn"),"Login verified through userinfo");check(status.getJSONArray("books").length()==1,"Initial cloud pull");check(fake.writes==0,"Login never pushes local favorites");
            status=account.change(200,true);check(status.getJSONArray("books").length()==2&&fake.writes==1,"Add and read back");
            check(!status.getBoolean("website"),"API identity never becomes a website session");
            fake.loginCode="2";boolean rejected=false;try{account.login("invalid-fixture","SyntheticTestPassword");}catch(Exception e){rejected=e.getMessage().contains("用户名或邮箱不正确");}check(rejected&&account.status().getBoolean("signedIn"),"Wrong username has distinct error and preserves prior session");fake.loginCode="3";rejected=false;try{account.login("invalid-fixture","SyntheticTestPassword");}catch(Exception e){rejected=e.getMessage().contains("密码不正确");}check(rejected,"Wrong password has distinct error");fake.loginCode="1";
            fake.mutationCode="5";status=account.change(200,true);check(status.getJSONArray("books").length()==2,"Already collected response requires and passes readback");fake.mutationCode="7";status=account.change(998,false);check(status.getJSONArray("books").length()==2,"Already removed response requires and passes readback");fake.mutationCode="1";
            fake.blockApiWrite=true;rejected=false;try{account.change(997,true);}catch(Exception e){rejected=true;}check(rejected&&account.status().getJSONArray("books").length()==2,"Success code without membership change is never confirmed");fake.blockApiWrite=false;
            fake.books.remove(129);status=account.pull();check(status.getJSONArray("books").length()==1,"External deletion pulls down");
            status=account.change(200,false);check(status.getJSONArray("books").length()==0,"Confirmed authoritative empty shelf");
            account.change(300,true);fake.mode="html";boolean failed=false;try{account.pull();}catch(Exception e){failed=true;}check(failed&&account.status().getJSONArray("books").length()==1,"Malformed page never erases shelf");
            fake.mode="other";failed=false;try{account.pull();}catch(Exception e){failed=true;}check(failed&&account.status().getJSONArray("books").length()==1,"Other XML metadata is never an empty shelf");
            fake.mode="expired";failed=false;try{account.pull();}catch(Exception e){failed=true;}check(failed&&!account.status().getBoolean("signedIn")&&account.status().getJSONArray("books").length()==1,"Expired auth preserves last shelf");
            fake.uid=202;fake.mode="html";status=account.login("synthetic-second","SyntheticTestPassword");check(status.getJSONObject("profile").getInt("uid")==202&&status.getJSONArray("books").length()==0,"Account switch cannot inherit old cloud shelf");
            fake.mode="ok";account.pull();String raw=context.getSharedPreferences(STORE,0).getAll().toString();check(!raw.contains(TOKEN)&&!raw.contains("SyntheticTestPassword"),"Secret not stored in plaintext");
            WenkuAccount restored=new WenkuAccount(context,fake,STORE,ALIAS);check(restored.status().getBoolean("signedIn"),"Keystore session restores");restored.logout();check(!restored.status().getBoolean("signedIn"),"Logout clears session");
            WebFake web=new WebFake();context.getSharedPreferences(STORE,0).edit().clear().commit();
            WenkuAccount webAccount=new WenkuAccount(context,(action,cookie)->{web.apiCalls++;throw new AssertionError("Website cookie must never enter the API transport");},web,STORE,ALIAS);
            status=webAccount.adoptWebsite();check(status.getBoolean("website")&&status.getJSONArray("books").length()==2&&web.apiCalls==0,"Independent website identity and grouped shelf");
            web.challenge=true;failed=false;try{webAccount.pull();}catch(Exception e){failed=true;}status=webAccount.status();check(failed&&status.getBoolean("signedIn")&&status.getBoolean("verificationRequired")&&status.getJSONArray("books").length()==2,"Verification pauses sync without discarding login or shelf");
            web.challenge=false;status=webAccount.pull();check(!status.getBoolean("verificationRequired"),"Successful verification clears paused status");
            webAccount.change(129,false);check(web.lastMutation.contains("delid=70001")&&!web.lastMutation.contains("delid=129"),"Removal uses website row id");
            status=webAccount.change(300,true);check(status.getJSONArray("books").length()==2,"Website add confirmed by shelf readback");
            web.blockWrite=true;failed=false;try{webAccount.change(300,false);}catch(Exception e){failed=true;}check(failed&&web.books.containsKey(300),"Received HTML is not write confirmation");web.blockWrite=false;
            web.incomplete=true;failed=false;try{webAccount.pull();}catch(Exception e){failed=true;}check(failed&&webAccount.status().getJSONArray("books").length()==2,"Incomplete website shelf preserved");web.incomplete=false;
            web.uid=402;failed=false;try{webAccount.pull();}catch(Exception e){failed=true;}check(failed&&webAccount.status().getJSONObject("profile").getString("uid").equals("401"),"Website account switch cannot alter old snapshot");
            raw=context.getSharedPreferences(STORE,0).getAll().toString();check(!raw.contains("WebsiteSyntheticOnly"),"Website encrypted session snapshot");WenkuAccount legacyWithoutBrowser=new WenkuAccount(context,(action,cookie)->{throw new AssertionError("Retired website cookies must not reach API");},STORE,ALIAS);JSONObject legacyStatus=legacyWithoutBrowser.status();check(!legacyStatus.getBoolean("signedIn")&&legacyStatus.getBoolean("requiresApiLogin")&&legacyStatus.getJSONArray("books").length()==2,"Retired browser identity keeps shelf and requests API reauthentication");webAccount.logout();
            out.putString("report",new JSONObject().put("passed",true).put("retiredWebsiteMigrationKeepsShelf",true).put("apiLoginErrorsDistinct",true).put("apiSessionSeparateFromWebsite",true).put("alreadyAddedOrRemovedRequiresReadback",true).put("unconfirmedApiWritesRejected",true).put("verificationPausesSyncAndKeepsShelf",true).put("websiteCookieSeparateFromApi",true).put("websiteGroupedShelfAndRowIds",true).put("websiteMembershipReadbackRequired",true).put("noChapterOrBookmarkWrites",true).put("websiteIncompleteShelfAndAccountMismatchPreserved",true).put("transport","synthetic fixture; no real account used").put("loginProfileValidation",true).put("noImplicitUploads",true).put("addRemoveAndExternalChanges",true).put("emptyShelfDistinctFromFailure",true).put("expiredSessionPreservesShelf",true).put("accountIsolation",true).put("encryptedSessionRestores",true).put("passwordNotPersisted",true).toString());finish(Activity.RESULT_OK,out);
        }catch(Throwable error){out.putString("failure",error.toString());finish(Activity.RESULT_CANCELED,out);}
        finally{context.getSharedPreferences(STORE,0).edit().clear().commit();try{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);if(keys.containsAlias(ALIAS))keys.deleteEntry(ALIAS);}catch(Exception ignored){}}
    }
}
