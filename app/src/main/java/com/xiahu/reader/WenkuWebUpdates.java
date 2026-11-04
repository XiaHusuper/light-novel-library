package com.xiahu.reader;

import android.app.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in official updates browser. No ReaderNative, account writes, or chapter requests. */
class WenkuWebUpdates {
    static final String BASE="https://www.wenku8.net",LOGIN=BASE+"/login.php",IDENTITY=BASE+"/userdetail.php?charset=gbk";
    final WebView web;
    private final Activity activity;
    private final FrameLayout parking;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final String parser,background;
    private Pending pending;
    private Dialog dialog;
    private TextView status,statusTitle;
    private ProgressBar spinner;
    private String diagnostics="尚未读取页面结构";
    private String owner="";
    private boolean verified,destroyed;
    private long lastLoginAt;
    private static final class Pending {
        final CountDownLatch done=new CountDownLatch(1);final int page;final WenkuAccount.WebAccess access;
        final boolean visible;final String token=UUID.randomUUID().toString();String stage="identity";boolean posted,inPage;int checks,generation,httpStatus;JSONObject result;Exception error;
        Pending(int page,WenkuAccount.WebAccess access,boolean visible){this.page=page;this.access=access;this.visible=visible;}
    }
    WenkuWebUpdates(Activity activity,FrameLayout parking)throws Exception{this(activity,parking,"");}
    WenkuWebUpdates(Activity activity,FrameLayout parking,String cookieOwner)throws Exception{
        this.activity=activity;this.parking=parking;this.owner=cookieOwner==null?"":cookieOwner;
        try(InputStream in=activity.getAssets().open("official-updates.js")){parser=new String(ApiClient.readLimited(in,64000),StandardCharsets.UTF_8);}
        try(InputStream in=activity.getAssets().open("official-background.js")){background=new String(ApiClient.readLimited(in,64000),StandardCharsets.UTF_8);}
        web=new WebView(activity);web.setBackgroundColor(Color.WHITE);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setSaveFormData(false);s.setCacheMode(WebSettings.LOAD_NO_CACHE);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){
                if(!request.isForMainFrame())return false;
                if(pending!=null&&pending.inPage)return true;
                Uri uri=request.getUrl();
                if(pending!=null&&"www.wenku8.net".equals(uri.getHost())&&("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))&&("/index.php".equals(uri.getPath())||"/".equals(uri.getPath()))){v.loadUrl(IDENTITY);return true;}
                if("http".equals(uri.getScheme())&&"GET".equals(request.getMethod())){Uri secure=uri.buildUpon().scheme("https").build();if(allowed(secure)){v.loadUrl(secure.toString());return true;}}
                if(!allowed(uri)){message("该入口仅用于官网今日更新及登录页面。");return true;}return false;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                if(request.isForMainFrame()&&!allowed(request.getUrl()))return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
                return intercept(request);
            }
            @Override public void onPageStarted(WebView v,String url,android.graphics.Bitmap icon){
                Pending p=pending;if(p!=null){p.checks=0;p.generation++;p.httpStatus=0;}
            }
            @Override public void onReceivedHttpError(WebView v,WebResourceRequest request,WebResourceResponse response){
                if(request.isForMainFrame()&&pending!=null)pending.httpStatus=response.getStatusCode();
            }
            @Override public void onPageFinished(WebView v,String url){
                Pending p=pending;if(p==null||p.inPage||!url.equals(v.getUrl())||!allowed(Uri.parse(url)))return;
                // The site's legacy form may contain an absolute HTTP login action.
                v.evaluateJavascript("(function(){document.querySelectorAll('form').forEach(function(f){try{var u=new URL(f.action,location.href);if(u.hostname==='www.wenku8.net'&&u.pathname==='/login.php'&&u.protocol==='http:'){u.protocol='https:';f.action=u.href;}}catch(e){}});})()",null);
                inspect(p,url);
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest r,WebResourceError e){if(r.isForMainFrame())fail(new IOException("官网连接失败，请关闭后重试。"));}
            @Override public void onReceivedSslError(WebView v,SslErrorHandler handler,android.net.http.SslError error){handler.cancel();fail(new IOException("官网安全连接未通过，请稍后重试。"));}
        });
        park();
    }
    /** Instrumentation can intercept synthetic fixtures; production returns null. */
    WebResourceResponse intercept(WebResourceRequest request){return null;}
    static String pageUrl(int page){return BASE+"/modules/article/toplist.php?sort=lastupdate&page="+page+"&charset=gbk";}
    static boolean allowed(Uri u){
        if(!"https".equals(u.getScheme())||!"www.wenku8.net".equals(u.getHost())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))return false;
        String path=u.getPath();return path!=null&&path.startsWith("/cdn-cgi/")||"/login.php".equals(path)||"/userdetail.php".equals(path)||"/checkcode.php".equals(path)||"/modules/article/toplist.php".equals(path)&&"lastupdate".equals(u.getQueryParameter("sort"));
    }
    static boolean authName(String name){return name.equals("PHPSESSID")||name.startsWith("jieqiUser")&&!name.equals("jieqiUserCharset");}
    static List<String> handoffCookies(String header){
        List<String> result=new ArrayList<>();for(String item:header.split(";")){String[] p=item.trim().split("=",2);if(p.length==2&&(p[0].equals("PHPSESSID")||p[0].equals("jieqiUserInfo"))&&p[1].length()<=4096&&!p[1].matches("[\\s\\S]*[\\r\\n][\\s\\S]*"))result.add(p[0]+"="+p[1]+"; Path=/; Secure; HttpOnly");}return result;
    }
    static byte[] loginPost(WenkuAccount.WebAccess access)throws Exception{
        return ("username="+URLEncoder.encode(access.username,"GB18030")+"&password="+URLEncoder.encode(access.password,"GB18030")+"&action=login&usecookie=0").getBytes(StandardCharsets.US_ASCII);
    }
    private void detach(){if(web.getParent() instanceof ViewGroup)((ViewGroup)web.getParent()).removeView(web);}
    private void park(){detach();web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);parking.addView(web,0,new FrameLayout.LayoutParams(-1,-1));}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private android.graphics.drawable.GradientDrawable surface(int color,int radius,boolean border){
        android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));if(border)shape.setStroke(dp(1),Color.rgb(228,230,220));return shape;
    }
    private Button action(String label,boolean primary){
        int green=Color.rgb(53,92,77),paper=Color.rgb(255,253,247);Button button=new Button(activity);button.setText(label);button.setAllCaps(false);button.setTextSize(13);button.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);button.setTextColor(primary?paper:green);button.setPadding(dp(14),0,dp(14),0);button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(0);button.setMinimumHeight(0);button.setStateListAnimator(null);
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(primary?0x24ffffff:0x14355c4d),surface(primary?green:paper,14,!primary),null));return button;
    }
    private void show(){
        if(dialog!=null)return;
        final int paper=Color.rgb(246,244,236),card=Color.rgb(255,253,247),ink=Color.rgb(38,61,52),muted=Color.rgb(126,136,123),green=Color.rgb(53,92,77);
        dialog=new Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar);LinearLayout layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);layout.setBackgroundColor(paper);MainActivity.applySafeInsets(layout);
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(20),dp(14),dp(20),dp(14));
        ImageView mark=new ImageView(activity);mark.setImageResource(R.drawable.ic_launcher);mark.setContentDescription("轻小说文库");bar.addView(mark,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout identity=new LinearLayout(activity);identity.setOrientation(LinearLayout.VERTICAL);TextView title=new TextView(activity);title.setText("官网今日更新");title.setTextSize(18);title.setTextColor(ink);title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);identity.addView(title);
        TextView subtitle=new TextView(activity);subtitle.setText("WENKU8 · 轻小说文库");subtitle.setTextSize(10);subtitle.setTextColor(muted);subtitle.setLetterSpacing(.08f);LinearLayout.LayoutParams subParams=new LinearLayout.LayoutParams(-1,-2);subParams.topMargin=dp(4);identity.addView(subtitle,subParams);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.leftMargin=dp(11);bar.addView(identity,titleParams);
        Button close=action("关闭",false);close.setOnClickListener(v->dialog.dismiss());bar.addView(close,new LinearLayout.LayoutParams(dp(60),dp(44)));layout.addView(bar);
        LinearLayout info=new LinearLayout(activity);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(16),dp(16),dp(16),dp(16));info.setBackground(surface(card,20,true));
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);spinner=new ProgressBar(activity,null,android.R.attr.progressBarStyleSmall);spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(green));heading.addView(spinner,new LinearLayout.LayoutParams(dp(18),dp(18)));
        statusTitle=new TextView(activity);statusTitle.setText("正在读取书目");statusTitle.setTextColor(ink);statusTitle.setTextSize(15);statusTitle.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);LinearLayout.LayoutParams headingText=new LinearLayout.LayoutParams(-1,-2);headingText.leftMargin=dp(10);heading.addView(statusTitle,headingText);info.addView(heading);
        status=new TextView(activity);status.setTextColor(muted);status.setTextSize(13);status.setLineSpacing(dp(3),1);status.setMaxLines(5);status.setEllipsize(android.text.TextUtils.TruncateAt.END);status.setText("正在打开官网，确认后会自动回到书目列表。");LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(-1,-2);statusParams.topMargin=dp(10);info.addView(status,statusParams);
        LinearLayout actions=new LinearLayout(activity);Button retry=action("继续读取",true),copy=action("复制读取信息",false);
        retry.setOnClickListener(v->{Pending p=pending;if(p!=null){String url=web.getUrl();if(url!=null&&allowed(Uri.parse(url))){p.checks=0;statusTitle.setText("正在读取书目");spinner.setVisibility(View.VISIBLE);inspect(p,url);}}});
        copy.setOnClickListener(v->{((android.content.ClipboardManager)activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("官网读取信息",diagnosticText()));Toast.makeText(activity,"已复制页面结构信息",Toast.LENGTH_SHORT).show();});
        LinearLayout.LayoutParams primaryParams=new LinearLayout.LayoutParams(0,dp(44),1);primaryParams.rightMargin=dp(8);actions.addView(retry,primaryParams);actions.addView(copy,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,-2);actionParams.topMargin=dp(14);info.addView(actions,actionParams);
        LinearLayout.LayoutParams infoParams=new LinearLayout.LayoutParams(-1,-2);infoParams.setMargins(dp(16),0,dp(16),dp(12));layout.addView(info,infoParams);
        FrameLayout preview=new FrameLayout(activity);preview.setBackground(surface(Color.WHITE,20,true));preview.setClipToOutline(true);detach();web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);web.setOverScrollMode(View.OVER_SCROLL_NEVER);web.setVerticalScrollBarEnabled(false);preview.addView(web,new FrameLayout.LayoutParams(-1,-1));LinearLayout.LayoutParams previewParams=new LinearLayout.LayoutParams(-1,0,1);previewParams.setMargins(dp(16),0,dp(16),dp(16));layout.addView(preview,previewParams);
        dialog.setContentView(layout);dialog.setOnDismissListener(d->{dialog=null;status=null;statusTitle=null;spinner=null;if(!destroyed)park();if(pending!=null)finish(null,new IOException("已关闭官网读取，原列表保留。"));});
        dialog.show();Window window=dialog.getWindow();window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(paper));window.setStatusBarColor(paper);window.setNavigationBarColor(paper);window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);window.setLayout(-1,-1);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);layout.requestApplyInsets();
    }
    private void message(String message){
        Pending p=pending;if(p==null)return;boolean busy=message.startsWith("正在");
        if(!p.visible){if(!busy)finish(null,new IOException(message+" 可点查看官网处理。"));return;}
        if(dialog==null)show();if(status!=null)status.setText(message);if(statusTitle!=null)statusTitle.setText(busy?"正在连接官网":"请核对官网页面");if(spinner!=null)spinner.setVisibility(busy?View.VISIBLE:View.GONE);
    }

    static void clearAuth(Runnable completed){
        CookieManager manager=CookieManager.getInstance();String existing=manager.getCookie(BASE);List<String> names=new ArrayList<>();if(existing!=null)for(String part:existing.split(";")){String name=part.trim().split("=",2)[0];if(authName(name))names.add(name);}
        if(names.isEmpty()){completed.run();return;}final int[] left={names.size()*2};for(String name:names)for(String path:new String[]{"/","/modules/article/"})manager.setCookie(BASE,name+"=; Path="+path+"; Max-Age=0; Secure",ok->{if(--left[0]==0)completed.run();});
    }
    private void initialize(Pending p,boolean visible){
        if(destroyed){p.error=new IOException("页面已关闭");p.done.countDown();return;}
        if(pending!=null){p.error=new IOException("官网正在读取，请稍后重试。");p.done.countDown();return;}
        pending=p;if(visible)show();CookieManager.getInstance().setAcceptCookie(true);
        if(!owner.equals(p.access.uid)){
            owner=p.access.uid;verified=false;lastLoginAt=0;
            clearAuth(()->{
                if(pending!=p)return;List<String> cookies=handoffCookies(p.access.cookie);if(cookies.isEmpty()){load(p);return;}
                final int[] left={cookies.size()};for(String cookie:cookies)CookieManager.getInstance().setCookie(BASE,cookie,ok->{if(--left[0]==0&&pending==p)load(p);});
            });
        }else load(p);
    }
    private void load(Pending p){
        p.stage=verified?"updates":"identity";
        String current=web.getUrl();
        if(verified&&!p.visible&&current!=null&&allowed(Uri.parse(current))&&"/modules/article/toplist.php".equals(Uri.parse(current).getPath())){startBackground(p);return;}
        web.loadUrl(verified?pageUrl(p.page):IDENTITY);
    }
    private void startBackground(Pending p){
        p.inPage=true;
        web.evaluateJavascript("("+background+")(window,"+JSONObject.quote(p.token)+","+JSONObject.quote(pageUrl(p.page))+","+p.page+",("+parser+"))",raw->{if(pending==p&&p.inPage)pollBackground(p);});
    }
    private void pollBackground(Pending p){
        if(pending!=p||!p.inPage||destroyed)return;
        String script="(function(){var j=window.__lnlOfficialXHR;return j&&j.token==="+JSONObject.quote(p.token)+"&&j.done?JSON.stringify(j.value):null;})()";
        web.evaluateJavascript(script,raw->{
            if(pending!=p||!p.inPage||destroyed)return;
            if(raw==null||raw.equals("null")){main.postDelayed(()->pollBackground(p),100);return;}
            try{
                Object decoded=new JSONTokener(raw).nextValue();if(!(decoded instanceof String)||((String)decoded).length()>1000000)throw new IOException("官网后台响应不完整。");
                JSONObject envelope=new JSONObject((String)decoded);if(envelope.has("error"))throw new IOException(envelope.getString("error"));
                String address=envelope.getString("url");if(!allowed(Uri.parse(address)))throw new IOException("官网后台返回了不支持的页面，请点查看官网核对。");
                p.httpStatus=envelope.optInt("status")>=400?envelope.optInt("status"):0;
                JSONObject result=envelope.getJSONObject("data");endBackground(p);handle(p,address,result,false);
            }catch(Exception error){finish(null,error);}
        });
    }
    private void endBackground(Pending p){
        if(!p.inPage)return;p.inPage=false;
        web.evaluateJavascript("(function(){var j=window.__lnlOfficialXHR;if(j&&j.token==="+JSONObject.quote(p.token)+"){window.__lnlOfficialXHR=null;if(j.xhr&&j.xhr.readyState!==4)j.xhr.abort();}})()",null);
    }

    private void inspect(Pending p,String address){
        if(pending!=p)return;p.checks++;Uri uri=Uri.parse(address);boolean identity="/userdetail.php".equals(uri.getPath());
        if(!identity&&!"/modules/article/toplist.php".equals(uri.getPath())&&!"/login.php".equals(uri.getPath()))return;
        web.evaluateJavascript("JSON.stringify(("+parser+")(document,"+JSONObject.quote(identity?"identity":"updates")+",{page:"+p.page+"}))",raw->{
            if(pending!=p||!address.equals(web.getUrl()))return;
            try{
                Object decoded=new JSONTokener(raw).nextValue();if(!(decoded instanceof String)||((String)decoded).length()>1000000)throw new IOException("官网响应不完整。");JSONObject result=new JSONObject((String)decoded);
                handle(p,address,result,true);
            }catch(Exception error){fail(error);}
        });
    }
    private void handle(Pending p,String address,JSONObject result,boolean fromDocument){
        if(pending!=p)return;Uri uri=Uri.parse(address);if(!allowed(uri)){fail(new IOException("官网返回的页面不受支持。"));return;}boolean identity="/userdetail.php".equals(uri.getPath());
        recordDiagnostics(p,uri,result);
        try{
                if(result.has("gate")){
                    verified=false;
                    if(!p.posted&&!result.optBoolean("captcha")&&!p.access.username.isEmpty()&&!p.access.password.isEmpty()&&(lastLoginAt==0||System.currentTimeMillis()-lastLoginAt>60000)){
                        p.posted=true;lastLoginAt=System.currentTimeMillis();message("正在使用当前账号建立官网会话…");web.postUrl(LOGIN,loginPost(p.access));return;
                    }
                    message("App 登录尚未被官网认可。可在当前网页完成登录，再点继续读取；也可关闭保留原列表。");return;
                }
                if(result.has("error")){
                    if("/login.php".equals(uri.getPath())&&!result.optString("code").equals("verification")){p.stage="identity";web.loadUrl(IDENTITY);return;}
                    String code=result.optString("code");
                    if(fromDocument&&!identity&&Arrays.asList("empty","structure","pagination").contains(code)&&p.checks<4){
                        if(status!=null)status.setText("正在识别书目，封面无需等待…");int generation=p.generation;
                        main.postDelayed(()->{if(pending==p&&generation==p.generation&&address.equals(web.getUrl()))inspect(p,address);},p.checks*250L);return;
                    }
                    if(code.equals("verification"))verified=false;
                    message(result.getString("error")+"\n网页会保留，可点继续读取或复制读取信息；关闭后保留 App 原列表。");return;
                }
                if(identity){
                    if(!p.access.uid.equals(result.optString("uid")))throw new IOException("官网账号与 App 当前账号不同，未读取书目。请关闭后重新登录。");
                    verified=true;p.stage="updates";if(status!=null){status.setText("已确认官网账号，正在读取第 "+p.page+" 页…");statusTitle.setText("正在读取书目");spinner.setVisibility(View.VISIBLE);}web.loadUrl(pageUrl(p.page));return;
                }
                if(!verified){p.stage="identity";web.loadUrl(IDENTITY);return;}
                if(!"/modules/article/toplist.php".equals(uri.getPath())||!"lastupdate".equals(uri.getQueryParameter("sort")))return;
                String actual=uri.getQueryParameter("page");if(actual!=null&&!actual.equals(String.valueOf(p.page)))throw new IOException("官网页码不一致，未推进列表。");
                finish(result,null);
        }catch(Exception error){fail(error);}
    }
    /** Counts/enums only; no DOM body, title, account identity, cookies, credentials or URLs. */
    private void recordDiagnostics(Pending p,Uri uri,JSONObject result){
        try{
            String path=uri.getPath(),kind="/login.php".equals(path)?"login":"/userdetail.php".equals(path)?"identity":"updates";
            JSONObject clean=new JSONObject().put("build","0.8.3").put("pageKind",kind).put("requestedPage",p.page).put("httpError",p.httpStatus);
            String code=result.optString("code",result.has("gate")?"login":result.has("books")?"ok":"identity");if(!Arrays.asList("verification","login","identity","response","limited","structure","empty","pagination","page","ok").contains(code))code="response";clean.put("result",code);
            JSONObject stats=result.optJSONObject("diagnostics");if(stats!=null){for(String key:new String[]{"tables","grids","bookLinks","uniqueBooks","recognized","dated","pages","current","statPages","statCurrent","linkPages","labelPages","markedCurrent"})if(stats.has(key))clean.put(key,Math.max(0,Math.min(100000,stats.optInt(key))));if(stats.has("hasPagination"))clean.put("hasPagination",stats.optBoolean("hasPagination"));String pagingSource=stats.optString("paginationSource");if(Arrays.asList("stats","fraction","last-link","total-label","single-page").contains(pagingSource))clean.put("paginationSource",pagingSource);JSONArray pathTags=stats.optJSONArray("linkPath"),tags=new JSONArray();if(pathTags!=null)for(int i=0;i<Math.min(6,pathTags.length());i++){String tag=pathTags.optString(i);if(Arrays.asList("a","b","strong","span","div","p","h1","h2","h3","td","th","tr","tbody","thead","table","body").contains(tag))tags.put(tag);}clean.put("linkPath",tags);}
            diagnostics=clean.toString();
        }catch(Exception ignored){diagnostics="页面结构信息暂不可用";}
    }
    String diagnosticText(){return diagnostics;}
    private void fail(Exception error){finish(null,error);}
    private void finish(JSONObject result,Exception error){
        Pending p=pending;if(p==null)return;boolean inPage=p.inPage;endBackground(p);pending=null;p.result=result;p.error=error;if(error!=null&&!inPage)web.stopLoading();if(result!=null)CookieManager.getInstance().flush();if(dialog!=null)dialog.dismiss();p.done.countDown();
    }
    JSONObject page(int page,WenkuAccount.WebAccess access,boolean visible)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper()||page<1||page>1000)throw new IOException("无效的官网页码。");
        Pending p=new Pending(page,access,visible);main.post(()->initialize(p,visible));
        if(!p.done.await(120,TimeUnit.SECONDS)){main.post(()->{if(pending==p)finish(null,new IOException("官网读取超时，原列表保留。"));});throw new IOException("官网读取超时，原列表保留。");}
        if(p.error!=null)throw p.error;if(p.result==null)throw new IOException("官网尚未返回书目。");return p.result;
    }
    void clear(){main.post(()->{if(destroyed)return;finish(null,new IOException("账号已退出，官网读取已停止。"));owner="";verified=false;clearAuth(()->{});web.loadUrl("about:blank");web.clearHistory();});}
    void destroy(){if(destroyed)return;destroyed=true;finish(null,new IOException("页面已关闭。"));if(dialog!=null)dialog.dismiss();detach();web.destroy();}
}
