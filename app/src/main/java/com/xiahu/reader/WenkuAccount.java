package com.xiahu.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import org.w3c.dom.*;
import java.io.IOException;
import java.net.HttpCookie;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Account operations and recovery are serialized; sensitive state is Keystore encrypted. */
final class WenkuAccount {
    interface Transport {ApiClient.Exchange call(String action,String cookie)throws Exception;}
    interface Website {JSONObject page(String path,String kind,JSONObject expected)throws Exception;String cookie();void restore(String cookie)throws Exception;void clear();default void persist()throws Exception{}}
    static final class WebsiteException extends IOException {final String code;WebsiteException(String code,String message){super(message);this.code=code;}}
    private final Transport transport;
    private final Website website;
    private final SharedPreferences prefs;
    private final String alias;
    private JSONObject session=new JSONObject();
    private boolean expired,verificationRequired,recovering;
    WenkuAccount(Context context,Transport transport)throws Exception{this(context,transport,null,"wenku_account","ShiyeWenkuSession");}
    WenkuAccount(Context context,Transport transport,Website website)throws Exception{this(context,transport,website,"wenku_account","ShiyeWenkuSession");}
    WenkuAccount(Context context,Transport transport,String store,String alias)throws Exception{
        this(context,transport,null,store,alias);
    }
    WenkuAccount(Context context,Transport transport,Website website,String store,String alias)throws Exception{
        this.transport=transport;this.website=website;this.alias=alias;prefs=context.getSharedPreferences(store,Context.MODE_PRIVATE);
        String value=prefs.getString("encrypted","");
        if(!value.isEmpty())try{String[] parts=value.split(":",2);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));session=new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){prefs.edit().clear().apply();}
        expired=session.optBoolean("recoveryBlocked");
    }
    private javax.crypto.SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(alias)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}
        return(javax.crypto.SecretKey)store.getKey(alias,null);
    }
    private void save()throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String encoded=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(session.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString("encrypted",encoded).commit())throw new IOException("无法安全保存登录状态，请重试");
    }
    synchronized JSONObject status()throws Exception{return new JSONObject().put("signedIn",!session.optString("cookie").isEmpty()&&!expired&&!(isWebsite()&&website==null)).put("requiresApiLogin",isWebsite()&&website==null).put("website",isWebsite()).put("expired",expired).put("verificationRequired",verificationRequired).put("profile",displayProfile(session.optJSONObject("profile"))).put("books",session.optJSONArray("books")==null?new JSONArray():session.getJSONArray("books")).put("syncedAt",session.optLong("syncedAt"));}
    private boolean isWebsite(){return session.optString("mode").equals("website");}
    synchronized JSONObject adoptWebsite()throws Exception{
        if(website==null)throw new IOException("官网会话暂不可用");
        website.restore("");
        JSONObject profile=website.page("/userdetail.php?charset=gbk","profile",null).getJSONObject("profile");
        String cookie=website.cookie();if(cookie.isEmpty())throw new IOException("请先在官方网页完成登录");
        JSONObject next=new JSONObject().put("mode","website").put("cookie",cookie).put("profile",profile).put("books",new JSONArray());
        JSONObject priorProfile=session.optJSONObject("profile");if(priorProfile!=null&&priorProfile.optString("uid").equals(profile.optString("uid")))next.put("books",session.optJSONArray("books")==null?new JSONArray():session.getJSONArray("books"));
        JSONObject previous=session;session=next;expired=false;verificationRequired=false;try{website.persist();save();}catch(Exception e){session=previous;throw e;}
        try{return pullWebsite(false);}catch(Exception e){markWebsiteError(e);return status().put("warning","已确认官网登录；书架暂未同步："+e.getMessage());}
    }
    synchronized JSONObject login(String username,String password)throws Exception{return loginSession(username,password,"");}
    private JSONObject loginSession(String username,String password,String expectedUid)throws Exception{
        username=username.trim();if(username.isEmpty()||username.length()>254||password.isEmpty()||password.length()>128)throw new IOException("请输入有效的文库账号和密码");
        String action="action="+(username.contains("@")?"loginemail":"login")+"&username="+ApiClient.encoded(username)+"&password="+ApiClient.encoded(password);
        ApiClient.Exchange response=transport.call(action,"");
        String result=response.body.trim();if(!result.equals("1"))throw new IOException(result.equals("2")?"用户名或邮箱不正确":result.equals("3")?"密码不正确":result.equals("4")?"来源未建立登录会话，请重新登录":"账号登录未完成，请稍后重试");
        String cookie=mergeCookies("",response.cookies);
        if(cookie.isEmpty())throw new IOException("来源未返回账号会话，请稍后重新登录");
        return adopt(cookie,expectedUid,new JSONObject().put("username",username).put("password",password));
    }
    synchronized JSONObject adopt(String cookie)throws Exception{return adopt(cookie,"",null);}
    private JSONObject adopt(String cookie,String expectedUid,JSONObject credentials)throws Exception{
        if(cookie.isEmpty()||cookie.length()>24000||cookie.contains("\r")||cookie.contains("\n"))throw new IOException("未找到有效的网站登录状态");
        ApiClient.Exchange response=transport.call("action=userinfo",cookie);JSONObject profile=parseProfile(response.body);
        if(!expectedUid.isEmpty()&&!expectedUid.equals(profile.optString("uid")))throw new IOException("账号验证未通过，请重新登录");
        JSONObject next=new JSONObject().put("mode","api").put("cookie",mergeCookies(cookie,response.cookies)).put("profile",profile).put("books",new JSONArray());
        if(credentials!=null)next.put("credentials",credentials);
        if(recovering)next.put("recoveryAt",System.currentTimeMillis());
        // Validate before replacing an existing account. Do not merge a different user's shelf.
        String priorUid=session.optJSONObject("profile")==null?"":session.getJSONObject("profile").optString("uid");
        if(priorUid.equals(profile.optString("uid")))next.put("books",session.optJSONArray("books")==null?new JSONArray():session.getJSONArray("books"));
        JSONObject previous=session;session=next;expired=false;verificationRequired=false;
        try{save();}catch(Exception error){session=previous;throw error;}
        try{return pull();}catch(Exception ignored){return status().put("warning","已登录，书架暂时未能同步，请稍后重试");}
    }
    synchronized JSONObject pull()throws Exception{
        if(isWebsite())try{return pullWebsite(true);}catch(WebsiteException e){markWebsiteError(e);throw e;}
        requireSession();ApiClient.Exchange response=transport.call("action=bookcase&t=0",session.getString("cookie"));
        JSONArray books;
        try{books=parseShelf(response.body);}catch(Exception error){if(response.body.trim().equals("4")){expired=true;if(recoverSession())return status();throw new IOException("登录已失效，请重新登录；原书架已保留");}throw error;}
        String cookie=mergeCookies(session.getString("cookie"),response.cookies);
        session.put("cookie",cookie).put("books",books).put("syncedAt",System.currentTimeMillis());save();return status();
    }
    synchronized JSONObject change(int aid,boolean add)throws Exception{
        requireSession();if(aid<=0||aid>1000000)throw new IOException("无效书籍编号");
        if(isWebsite())try{return changeWebsite(aid,add);}catch(WebsiteException e){markWebsiteError(e);throw e;}
        ApiClient.Exchange response;
        try{response=transport.call("action=bookcase&do="+(add?"add":"del")+"&aid="+aid,session.getString("cookie"));}
        catch(Exception failure){throw new IOException("同步结果暂未确认，请刷新云书架；不会自动重复提交");}
        String code=response.body.trim();if(code.equals("4")){expired=true;if(recoverSession())return change(aid,add);throw new IOException("登录已失效，请重新登录");}
        if(!(code.equals("1")||code.equals(add?"5":"7")))throw new IOException(code.equals("6")?"文库书架已满，请先整理后重试":"来源未确认书架修改，请稍后同步核对");
        session.put("cookie",mergeCookies(session.getString("cookie"),response.cookies));
        JSONObject status;try{status=pull();}catch(Exception failure){throw new IOException("网站修改结果未确认，请稍后同步核对；原书架快照已保留");}
        if((find(aid)!=null)!=add)throw new IOException("网站书架尚未反映这次修改，请稍后同步核对");return status;
    }
    private JSONObject pullWebsite(boolean verify)throws Exception{
        requireSession();if(website==null)throw new IOException("请重新打开官方网页登录");website.restore(session.getString("cookie"));
        if(verify){JSONObject profile=website.page("/userdetail.php?charset=gbk","profile",null).getJSONObject("profile");if(!profile.optString("uid").equals(session.getJSONObject("profile").optString("uid")))throw new IOException("官网账号已变化，请重新完成网页登录");session.put("profile",displayProfile(profile));}
        JSONArray books=new JSONArray();Set<String> seen=new HashSet<>();LinkedHashSet<Integer> groups=new LinkedHashSet<>();groups.add(0);int total=-1;
        JSONObject first=website.page("/modules/article/bookcase.php?classid=0&charset=gbk","shelf",null);JSONArray options=first.optJSONArray("groups");if(options!=null)for(int i=0;i<options.length();i++){int group=options.getInt(i);if(group<0||group>5)throw new IOException("书架分组不完整，原书架已保留");groups.add(group);}
        for(int group:groups){int count=0,inGroup=-1,pages=1;
            for(int page=1;page<=pages;page++){
                JSONObject result=group==0&&page==1?first:website.page("/modules/article/bookcase.php?classid="+group+"&page="+page+"&charset=gbk","shelf",null);
                int currentTotal=result.getInt("total"),currentGroup=result.getInt("inGroup");if(total<0)total=currentTotal;if(inGroup<0)inGroup=currentGroup;if(total!=currentTotal||inGroup!=currentGroup)throw new IOException("同步期间书架有变化，请刷新重试；原书架已保留");
                pages=Math.max(pages,result.getInt("pages"));if(pages>30)throw new IOException("书架分页过多，原书架已保留");
                JSONArray items=result.getJSONArray("books");for(int i=0;i<items.length();i++){JSONObject book=items.getJSONObject(i);String id=book.getString("id");if(!seen.add(id))throw new IOException("书架分页出现重复，原书架已保留");book.put("group",group);books.put(book);count++;if(books.length()>2000)throw new IOException("账号书架过大");}
            }
            if(count!=inGroup)throw new IOException("书架分页数量不完整，原书架已保留");
        }
        if(books.length()!=total)throw new IOException("书架总数校验失败，原书架已保留");
        session.put("books",books).put("cookie",website.cookie()).put("syncedAt",System.currentTimeMillis());website.persist();verificationRequired=false;save();return status();
    }
    private JSONObject find(int aid){JSONArray books=session.optJSONArray("books");if(books!=null)for(int i=0;i<books.length();i++){JSONObject book=books.optJSONObject(i);if(book!=null&&book.optString("id").equals(String.valueOf(aid)))return book;}return null;}
    private JSONObject changeWebsite(int aid,boolean add)throws Exception{
        pullWebsite(true);JSONObject before=find(aid);if((add&&before!=null)||(!add&&before==null))return status();
        String path;
        if(add)path="/modules/article/addbookcase.php?bid="+aid;
        else path="/modules/article/bookcase.php?classid="+before.getInt("group")+"&delid="+before.getString("rowId");
        Exception uncertain=null;try{website.page(path,"action",null);}catch(Exception e){uncertain=e;}
        try{pullWebsite(true);}catch(Exception e){markWebsiteError(e);if(e instanceof WebsiteException)throw new WebsiteException(((WebsiteException)e).code,"网站修改结果未确认，请完成官网验证后同步核对；本机记录已保留");throw new IOException("网站修改结果未确认，请稍后同步核对；本机记录已保留");}
        JSONObject after=find(aid);boolean confirmed=add?after!=null:after==null;
        if(!confirmed)throw new IOException(uncertain==null?"网站未确认本次修改，请打开官方网页核对书架容量或账号权限":uncertain.getMessage());return status();
    }
    private void markWebsiteError(Exception e){if(e instanceof WebsiteException){String code=((WebsiteException)e).code;if(code.equals("verification"))verificationRequired=true;if(code.equals("login"))expired=true;}}
    synchronized JSONObject logout()throws Exception{if(!prefs.edit().clear().commit())throw new IOException("无法清除本机登录状态，请重试");session=new JSONObject();expired=false;verificationRequired=false;if(website!=null)website.clear();return status();}
    private static JSONObject displayProfile(JSONObject original)throws Exception{if(original==null)return null;JSONObject profile=new JSONObject(original.toString());for(String key:new String[]{"uname","nickname"}){String value=profile.optString(key).replaceAll("[（(]?\\s*留空则[\\s\\S]*$","").replaceAll("[（(]修改[\\s\\S]*$","").trim();profile.put(key,value);}return profile;}
    private boolean recoverSession()throws Exception{
        JSONObject credentials=session.optJSONObject("credentials");
        long now=System.currentTimeMillis(),last=session.optLong("recoveryAt");
        if(recovering||isWebsite()||credentials==null||session.optBoolean("recoveryBlocked")||last>0&&now-last<15*60*1000)return false;
        String uid=session.optJSONObject("profile")==null?"":session.getJSONObject("profile").optString("uid");
        if(uid.isEmpty())return false;
        JSONObject previous=new JSONObject(session.toString());recovering=true;
        try{
            loginSession(credentials.optString("username"),credentials.optString("password"),uid);
            if(expired)throw new IOException("账号登录未完成");
            session.put("recoveryAt",now);save();return true;
        }catch(Exception failure){
            session=previous;expired=true;session.put("recoveryBlocked",true).put("recoveryAt",now);save();return false;
        }finally{recovering=false;}
    }
    private void requireSession()throws Exception{if(isWebsite()&&website==null)throw new IOException("请使用账号密码重新登录，原书架已保留");if(expired&&!recoverSession())throw new IOException("登录已失效，请重新登录；原书架已保留");if(session.optString("cookie").isEmpty())throw new IOException("请先登录 Wenku8 账号");}
    static JSONObject parseProfile(String xml)throws Exception{
        if(xml.trim().equals("4"))throw new IOException("登录会话未生效，请重新登录");
        Document doc;try{doc=ApiClient.xml(xml);}catch(Exception e){throw new IOException("来源未返回有效账号资料");}
        if(!doc.getDocumentElement().getTagName().equals("metadata"))throw new IOException("来源返回了非账号页面，登录未确认");
        JSONObject profile=new JSONObject();NodeList entries=doc.getElementsByTagName("item");for(int i=0;i<entries.getLength();i++){Element e=(Element)entries.item(i);String name=e.getAttribute("name");if(Arrays.asList("uname","nickname","uid","score","experience","rank").contains(name))profile.put(name,e.getTextContent().trim());}
        if(profile.optInt("uid")<=0||profile.optString("uname").isEmpty())throw new IOException("账号资料不完整，登录未确认");return profile;
    }
    static JSONArray parseShelf(String xml)throws Exception{
        Document doc;try{doc=ApiClient.xml(xml);}catch(Exception e){throw new IOException("云书架响应无效，保留原书架");}
        if(!doc.getDocumentElement().getTagName().equals("metadata"))throw new IOException("来源未返回有效云书架，保留原书架");
        for(Node child=doc.getDocumentElement().getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element&&!child.getNodeName().equals("book"))throw new IOException("响应不是云书架，保留原书架");
        JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();NodeList books=doc.getElementsByTagName("book");
        if(books.getLength()>2000)throw new IOException("云书架超过本次读取上限");
        for(int i=0;i<books.getLength();i++){Element book=(Element)books.item(i);String id=book.getAttribute("aid");if(!id.matches("[1-9][0-9]{0,6}")||Integer.parseInt(id)>1000000)throw new IOException("云书架记录不完整，保留原书架");if(!seen.add(id))continue;int aid=Integer.parseInt(id);NodeList names=book.getElementsByTagName("name");String title=names.getLength()==0?"书籍 "+id:names.item(0).getTextContent().trim();
            result.put(new JSONObject().put("id",id).put("source","online").put("title",title).put("updated",book.getAttribute("date")).put("cover","https://img.wenku8.com/image/"+(aid/1000)+"/"+aid+"/"+aid+"s.jpg").put("favorite",true).put("cloud",true));
        }
        // A different metadata response must not be mistaken for an empty shelf.
        if(result.length()==0&&(doc.getElementsByTagName("item").getLength()>0||doc.getElementsByTagName("data").getLength()>0))throw new IOException("响应不是云书架，保留原书架");
        return result;
    }
    static String mergeCookies(String previous,List<String> values){
        Map<String,String> cookies=new LinkedHashMap<>();for(String part:previous.split(";")){String[] pair=part.trim().split("=",2);if(pair.length==2&&pair[0].matches("[a-zA-Z0-9_-]{1,64}")&&!pair[1].contains("\n"))cookies.put(pair[0],pair[1]);}
        for(String value:values)try{for(HttpCookie cookie:HttpCookie.parse(value)){if(!cookie.getName().matches("[a-zA-Z0-9_-]{1,64}")||cookie.getValue().length()>4096||cookie.getValue().contains("\r")||cookie.getValue().contains("\n"))continue;if(cookie.getMaxAge()==0)cookies.remove(cookie.getName());else cookies.put(cookie.getName(),cookie.getValue());}}catch(Exception ignored){}
        List<String> parts=new ArrayList<>();for(Map.Entry<String,String> e:cookies.entrySet())parts.add(e.getKey()+"="+e.getValue());return String.join("; ",parts);
    }
}
