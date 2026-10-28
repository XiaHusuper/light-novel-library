package com.xiahu.reader;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;

/** Browser session for official account pages; never exposes ReaderNative to a website. */
final class WebsiteClient implements WenkuAccount.Website {
    static final String BASE="https://www.wenku8.net";
    final WebView web;
    private final Activity activity;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final String parser;
    private Pending pending;
    private boolean restored;
    private static final class Pending {final CountDownLatch done=new CountDownLatch(1);final String kind;final JSONObject expected;JSONObject value;Exception error;Pending(String k,JSONObject e){kind=k;expected=e;}}
    WebsiteClient(Activity activity,FrameLayout root)throws Exception{
        this.activity=activity;
        try(InputStream input=activity.getAssets().open("website.js")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);parser=new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);}
        web=new WebView(activity);web.setBackgroundColor(Color.WHITE);web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setCacheMode(WebSettings.LOAD_NO_CACHE);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSaveFormData(false);s.setBuiltInZoomControls(true);s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                if(!r.isForMainFrame())return false;Uri u=r.getUrl();
                if("http".equals(u.getScheme())){Uri secure=u.buildUpon().scheme("https").build();if(official(secure)&&"GET".equals(r.getMethod())){v.loadUrl(secure.toString());return true;}}
                return !official(u);
            }
            @Override public void onPageFinished(WebView v,String url){
                if(!url.equals(v.getUrl())||!official(Uri.parse(url)))return;
                // Old official templates can embed absolute HTTP form actions on HTTPS pages.
                // Preserve all fields and normal browser submission, upgrading only official URLs.
                v.evaluateJavascript("(function(){function secure(f){try{var u=new URL(f.action,location.href);if(u.protocol==='http:'&&/(^|\\.)wenku8\\.(net|com)$/.test(u.hostname)){u.protocol='https:';f.action=u.href;}}catch(e){}}document.querySelectorAll('form').forEach(secure);if(!window.__shiyeSecureForms){window.__shiyeSecureForms=true;document.addEventListener('submit',function(e){if(e.target&&e.target.tagName==='FORM')secure(e.target);},true);}})()",null);
                if(pending!=null)extract(pending);
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())finish(null,new IOException("官网连接失败，请稍后重试"));}
        });
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));
    }
    static boolean official(Uri u){String h=u.getHost();return "https".equals(u.getScheme())&&h!=null&&(h.equals("wenku8.net")||h.endsWith(".wenku8.net")||h.equals("wenku8.com")||h.endsWith(".wenku8.com"));}
    void loginView(ViewGroup target){detach();web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);target.addView(web,new android.widget.LinearLayout.LayoutParams(-1,0,1));CookieManager.getInstance().setAcceptCookie(true);web.loadUrl(BASE+"/login.php");}
    void park(FrameLayout root){web.stopLoading();detach();web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);root.addView(web,0,new FrameLayout.LayoutParams(-1,-1));CookieManager.getInstance().flush();}
    private void detach(){if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);}
    private void extract(Pending request){
        if(pending!=request)return;
        web.evaluateJavascript("JSON.stringify(("+parser+")(document,"+JSONObject.quote(request.kind)+","+request.expected+"))",raw->{
            if(pending!=request)return;
            try{Object decoded=new JSONTokener(raw).nextValue();if(!(decoded instanceof String)||((String)decoded).length()>2000000)throw new IOException("官网响应不完整");JSONObject result=new JSONObject((String)decoded);if(result.has("error"))throw new WenkuAccount.WebsiteException(result.optString("code","response"),result.getString("error"));finish(result,null);}catch(Exception error){finish(null,error);}
        });
    }
    private void finish(JSONObject value,Exception error){Pending p=pending;if(p==null)return;pending=null;p.value=value;p.error=error;p.done.countDown();}
    @Override public JSONObject page(String path,String kind,JSONObject expected)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("网页请求不能阻塞界面");
        if(!path.startsWith("/")||path.startsWith("//")||path.contains("\r")||path.contains("\n"))throw new IOException("无效的官网路径");
        Pending p=new Pending(kind,expected==null?new JSONObject():expected);
        main.post(()->{if(pending!=null){p.error=new IOException("官网正在处理其他请求");p.done.countDown();return;}pending=p;CookieManager.getInstance().setAcceptCookie(true);web.loadUrl(BASE+path);});
        if(!p.done.await(35,TimeUnit.SECONDS)){main.post(()->{if(pending==p){web.stopLoading();finish(null,new IOException("官网连接超时，请打开官方网页登录核对"));}});throw new IOException("官网连接超时，请稍后重试");}
        if(p.error!=null)throw p.error;return p.value;
    }
    @Override public String cookie(){String value=CookieManager.getInstance().getCookie(BASE+"/modules/article/");return value==null?"":value;}
    @Override public void persist(){CookieManager.getInstance().flush();}
    @Override public void restore(String cookie)throws Exception{
        if(restored)return;CookieManager manager=CookieManager.getInstance();String existing=manager.getCookie(BASE+"/");
        // Keep Chromium's live cookies and their original attributes, including clearance state.
        if(existing!=null&&java.util.regex.Pattern.compile("(?:^|;\\s*)(?:PHPSESSID|jieqiUserInfo)=").matcher(existing).find()){restored=true;return;}
        java.util.List<String> parts=new java.util.ArrayList<>();
        for(String part:cookie.split(";")){String name=part.trim().split("=",2)[0];if(name.equals("PHPSESSID")||name.equals("jieqiUserInfo")||name.equals("jieqiVisitInfo"))parts.add(part.trim());}
        CountDownLatch done=new CountDownLatch(parts.size());
        main.post(()->{manager.setAcceptCookie(true);for(String part:parts)manager.setCookie(BASE,part+"; Path=/; Secure",ok->done.countDown());});
        if(!done.await(5,TimeUnit.SECONDS))throw new IOException("官网会话初始化超时");manager.flush();restored=true;
    }
    @Override public void clear(){restored=false;main.post(()->{web.stopLoading();web.loadUrl("about:blank");CookieManager.getInstance().removeAllCookies(null);web.clearCache(true);web.clearHistory();WebStorage.getInstance().deleteAllData();});}
    void destroy(){web.stopLoading();detach();web.destroy();}
}
