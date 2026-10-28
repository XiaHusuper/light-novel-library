package com.xiahu.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.widget.*;
import android.text.InputType;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.os.*;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView view;
    private BookmarkStore bookmarks;private ReaderSystemUi readerSystemUi;
    private FrameLayout root;
    private final ApiClient api = new ApiClient();
    private LocalBooks local;
    private OfflineCache offline;
    private TagCatalog tags;
    private WenkuAccount account;
    private final ExecutorService accounts=Executors.newSingleThreadExecutor();
    private boolean loginShowing;
    private SharedPreferences prefs;
    private final ExecutorService network = Executors.newFixedThreadPool(4);
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private String importRequest;
    private volatile boolean alive = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("reader_state", MODE_PRIVATE);
        local = new LocalBooks(this);bookmarks=new BookmarkStore(this);
        tags=new TagCatalog(this,api);
        try{offline=new OfflineCache(this,api);}catch(Exception ignored){}
        root=new FrameLayout(this);
        try{account=new WenkuAccount(this,api::exchange);}catch(Exception ignored){}
        view = new ReaderWebView(this);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        view.setBackgroundColor(Color.rgb(246, 244, 236));
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setTextZoom(100);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        view.clearCache(true);
        view.addJavascriptInterface(new Bridge(), "ReaderNative");
        view.setWebViewClient(new ContentClient());
        view.setWebChromeClient(new WebChromeClient());
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        readerSystemUi=new ReaderSystemUi(this,root);
        setContentView(root);
        root.requestApplyInsets();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        view.loadUrl("https://" + HOST + "/index.html");
    }

    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        view.evaluateJavascript("window.App ? window.App.back() : false", handled -> {
            if (!"true".equals(handled)) finish();
        });
    }

    private void reply(String id, Object result, Throwable error) {
        if (!alive) return;
        try {
            JSONObject response = new JSONObject().put("id", id);
            if (error == null) response.put("ok", true).put("data", result == null ? JSONObject.NULL : result);
            else {
                String message = error.getMessage();
                if (error instanceof java.net.SocketTimeoutException) message = "连接超时，请检查网络后重试";
                if (message == null || message.length() > 220) message = "暂时无法完成，请重试";
                response.put("ok", false).put("error", message);if(error instanceof WenkuAccount.WebsiteException)response.put("code",((WenkuAccount.WebsiteException)error).code);
            }
            String script = "window.__nativeResponse(" + response.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
            runOnUiThread(() -> { if (alive) view.evaluateJavascript(script, null); });
        } catch (Exception ignored) {}
    }

    final class Bridge {
        @JavascriptInterface public void request(String id, String operation, String json) {
            if (!alive || id.length() > 80 || json.length() > 2 * 1024 * 1024) return;
            if ("import".equals(operation) || "importFolder".equals(operation)) {
                runOnUiThread(() -> pickFiles(id, "importFolder".equals(operation)));
                return;
            }
            if("accountLogin".equals(operation)){runOnUiThread(()->showLogin(id));return;}
            ExecutorService executor = operation.startsWith("account")?accounts:Arrays.asList("bootstrap", "saveState", "localChapter", "localOrder", "removeLocal", "storage", "bookmarkSave", "bookmarkRemove", "copyText", "readerSystemUi", "aboutInfo", "aboutNotices", "openProjectPage").contains(operation) ? files : network;
            executor.execute(() -> {
                try {
                    JSONObject input = new JSONObject(json);
                    if(operation.startsWith("account")&&input.has("expectedUid")&&account!=null){JSONObject profile=account.status().optJSONObject("profile");String uid=profile==null?"":profile.optString("uid");if(!input.optString("expectedUid").equals(uid))throw new IOException("账号已切换，请重新操作");}
                    Object result;
                    switch (operation) {
                        case "bootstrap": result = new JSONObject().put("state", new JSONObject(prefs.getString("state", "{}")))
                            .put("bookmarks",bookmarks.all()).put("localBooks", local.all()).put("localOrders",local.orders()).put("offlineCache",offline==null?new JSONObject():offline.status()).put("account",account==null?new JSONObject().put("signedIn",false):account.status()).put("version", "0.7.0"); break;
                        case "aboutInfo": result=ProjectInfo.info(MainActivity.this);break;
                        case "aboutNotices": result=ProjectInfo.notices(MainActivity.this);break;
                        case "openProjectPage": {Uri target=ProjectInfo.page(input.optString("page"));CountDownLatch opened=new CountDownLatch(1);Exception[] failure=new Exception[1];runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,target));}catch(ActivityNotFoundException e){failure[0]=new IOException("请安装浏览器后打开 GitHub");}finally{opened.countDown();}});if(!opened.await(3,TimeUnit.SECONDS))throw new IOException("暂未打开浏览器，请重试");if(failure[0]!=null)throw failure[0];result=new JSONObject().put("opened",true);break;}
                        case "bookmarkSave": result=bookmarks.save(input);break;
                        case "bookmarkRemove": result=bookmarks.remove(input.optString("id"));break;
                        case "copyText": {String copy=input.optString("text");if(copy.length()>100000)throw new IOException("选中文字过长");((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("小说摘录",copy));result=new JSONObject().put("copied",true);break;}
                        case "readerSystemUi": {boolean active=input.optBoolean("reading"),hide=input.optBoolean("hideStatusBar");String paper=input.optString("theme","paper");CountDownLatch applied=new CountDownLatch(1);runOnUiThread(()->{((ReaderWebView)view).reading=active;readerSystemUi.configure(active,hide,paper);applied.countDown();});if(!applied.await(3,TimeUnit.SECONDS))throw new IOException("显示设置暂未生效");result=new JSONObject().put("hidden",active&&hide);break;}
                        case "accountStatus": if(account==null)throw new IOException("本机安全存储暂不可用"); result=account.status();break;
                        case "accountPull": if(account==null)throw new IOException("本机安全存储暂不可用");result=account.pull();break;
                        case "accountAdd": case "accountRemove": if(account==null)throw new IOException("本机安全存储暂不可用");result=account.change(onlineId(input),operation.equals("accountAdd"));break;
                        case "accountLogout": if(account==null)throw new IOException("本机安全存储暂不可用");result=account.logout();break;
                        case "cacheStatus": if(offline==null)throw new IOException("缓存目录暂不可用");result=offline.status();break;
                        case "cacheStart": if(offline==null)throw new IOException("缓存目录暂不可用");int cacheId=onlineId(input);result=offline.start(cacheId,input.getJSONObject("book"),catalog(cacheId,false),input.optString("volume"),input.optBoolean("images",true),Math.max(0,input.optInt("chapterId")));break;
                        case "cacheCancel": if(offline==null)throw new IOException("缓存目录暂不可用");result=offline.cancel();break;
                        case "cacheRemove": if(offline==null)throw new IOException("缓存目录暂不可用");result=offline.remove(input.optInt("id"));break;
                        case "cacheLimit": if(offline==null)throw new IOException("缓存目录暂不可用");result=offline.limit(input.getInt("limitMiB"));break;
                        case "tagQuery": result=tags.query(input.optJSONArray("tags"),input.optString("mode","all"),input.optString("query"),input.optInt("limit",30),input.optBoolean("start"),input.optBoolean("refresh"));break;
                        case "tagPause": result=tags.pause();break;
                        case "feed": case "search": case "author": {
                            int page = Math.max(1, Math.min(1000, input.optInt("page", 1)));
                            String sort = input.optString("sort", "lastupdate");
                            if (!ApiClient.SORTS.contains(sort)) throw new IOException("不支持的榜单");
                            String text=input.optString("query", "").trim();if(text.length()>150)throw new IOException("搜索词过长");result = api.list(text,sort,page,operation.equals("author")?"author":"articlename");break;
                        }
                        case "metadata": result = api.metadata(onlineId(input)); break;
                        case "intro": result = api.introduction(onlineId(input)); break;
                        case "catalog": result = catalog(onlineId(input), input.optBoolean("refresh")); break;
                        case "chapter": {int aid=onlineId(input),cid=input.getInt("chapterId");JSONObject cached=offline==null?null:offline.chapter(aid,cid);result=cached==null?api.chapter(aid,cid):cached;break;}
                        case "localOrder": result=local.order(input);break;
                        case "localChapter": result = local.chapter(input.getString("id"), input.getInt("index")); break;
                        case "removeLocal": {
                            JSONArray ids=input.optJSONArray("ids");
                            if(ids!=null){for(int i=0;i<Math.min(200,ids.length());i++)local.remove(ids.getString(i));}
                            else local.remove(input.getString("id")); result=local.all();break;
                        }
                        case "saveState": saveState(input); result = new JSONObject().put("saved", true); break;
                        case "storage": result = new JSONObject().put("cacheBytes", size(getCacheDir()))
                            .put("importBytes", size(new File(getFilesDir(), "imports")))
                            .put("onlineBodiesSaved",offline!=null&&offline.status().optLong("bytes")>0).put("manualCacheBytes",offline==null?0:offline.status().optLong("bytes")); break;
                        default: throw new IOException("不支持的操作");
                    }
                    reply(id, result, null);
                } catch (Throwable error) { reply(id, null, error); }
            });
        }
    }

    private static int onlineId(JSONObject input) throws Exception {
        int id = Integer.parseInt(input.getString("id"));
        if (id <= 0 || id > 1000000) throw new IOException("无效书籍编号");
        return id;
    }

    private synchronized JSONObject catalog(int id, boolean refresh) throws Exception {
        if(!refresh&&offline!=null){JSONObject available=offline.catalog(id);if(available!=null)return available;}
        JSONObject saved = new JSONObject(prefs.getString("catalogs", "{}"));
        JSONObject cached = saved.optJSONObject(String.valueOf(id));
        if (!refresh && cached != null && System.currentTimeMillis() - cached.optLong("savedAt") < 15 * 60 * 1000) return cached;
        // Only directory metadata is stored; never chapter text or image bytes.
        JSONObject value = api.catalog(id).put("savedAt", System.currentTimeMillis());
        if(offline!=null)try{offline.updateCatalog(id,value);}catch(Exception ignored){}
        if (value.toString().length() > 1400000) return value;
        saved.put(String.valueOf(id), value);
        while (saved.length() > 24 || saved.toString().length() > 1500000) {
            String oldest = null; long time = Long.MAX_VALUE;
            Iterator<String> keys = saved.keys();
            while (keys.hasNext()) { String key = keys.next(); long stamp = saved.getJSONObject(key).optLong("savedAt"); if (stamp < time) { time = stamp; oldest = key; } }
            if (oldest == null || oldest.equals(String.valueOf(id))) break;
            saved.remove(oldest);
        }
        prefs.edit().putString("catalogs", saved.toString()).apply();
        return value;
    }

    private synchronized void saveState(JSONObject input) throws Exception {
        JSONObject state = new JSONObject();
        JSONObject settings = input.optJSONObject("settings");
        if (settings != null) state.put("settings", settings);
        JSONArray shelf = input.optJSONArray("shelf");
        JSONArray kept = new JSONArray();
        if (shelf != null) for (int index = 0; index < Math.min(300, shelf.length()); index++) {
            JSONObject original = shelf.getJSONObject(index), clean = new JSONObject();
            for (String key : new String[]{"id", "source", "title", "author", "cover", "tags", "status", "updated", "summary", "addedAt", "favorite"}) {
                if (original.has(key)) {
                    Object value = original.get(key);
                    if (value instanceof String && ((String) value).length() > 6000) value = ((String) value).substring(0, 6000);
                    clean.put(key, value);
                }
            }
            kept.put(clean);
        }
        state.put("shelf", kept);
        state.put("history",cleanHistory(input.optJSONArray("history")));
        state.put("learning",cleanLearning(input.optJSONObject("learning")));
        JSONObject preference=input.optJSONObject("preference"), cleanPreference=new JSONObject();
        if(preference!=null){
            for(String name:new String[]{"likes","avoid"}){
                JSONArray values=preference.optJSONArray(name),clean=new JSONArray();
                if(values!=null)for(int i=0;i<Math.min(100,values.length());i++){String value=values.optString(i);if(value.length()<=40)clean.put(value);}
                cleanPreference.put(name,clean);
            }
            JSONObject feedback=preference.optJSONObject("feedback"),clean=new JSONObject();
            if(feedback!=null){Iterator<String> keys=feedback.keys();int count=0;while(keys.hasNext()&&count++<300){String key=keys.next(),value=feedback.optString(key);if(key.length()<90&&(value.equals("like")||value.equals("hide")))clean.put(key,value);}}
            cleanPreference.put("feedback",clean);
        }
        state.put("preference",cleanPreference);
        JSONObject progress = input.optJSONObject("progress"), retained = new JSONObject();
        if (progress != null) {
            ArrayList<String> keys = new ArrayList<>(); progress.keys().forEachRemaining(keys::add);
            keys.sort((a, b) -> Long.compare(progress.optJSONObject(b).optLong("time"), progress.optJSONObject(a).optLong("time")));
            for (String key : keys.subList(0, Math.min(200, keys.size()))) {
                JSONObject item = progress.getJSONObject(key);
                JSONObject clean=new JSONObject().put("index", Math.max(0, item.optInt("index")))
                    .put("chapterKey", item.optString("chapterKey", "").substring(0,Math.min(200,item.optString("chapterKey", "").length())))
                    .put("ratio", Math.max(0, Math.min(1, item.optDouble("ratio"))))
                    .put("time", item.optLong("time"))
                    .put("chapterId",Math.max(0,item.optInt("chapterId")))
                    .put("chapterTitle",item.optString("chapterTitle").substring(0,Math.min(200,item.optString("chapterTitle").length())));
                JSONObject anchor=item.optJSONObject("anchor");if(anchor!=null&&anchor.optString("key").matches("[it]:[a-f0-9]{16}:[0-9]{1,6}"))clean.put("anchor",new JSONObject().put("key",anchor.getString("key")).put("index",Math.max(0,Math.min(100000,anchor.optInt("index")))).put("fragment",Math.max(0,Math.min(100000,anchor.optInt("fragment")))).put("fraction",Math.max(0,Math.min(1,anchor.optDouble("fraction")))).put("at",Math.max(0,Math.min(1,anchor.optDouble("at")))).put("headroom",Math.max(0,Math.min(1,anchor.optDouble("headroom")))));
                JSONArray sourceHints=item.optJSONArray("imageHints"),hints=new JSONArray();if(sourceHints!=null)for(int i=0;i<Math.min(80,sourceHints.length());i++){JSONArray hint=sourceHints.optJSONArray(i);if(hint!=null&&hint.optString(0).matches("[a-f0-9]{16}")&&hint.optDouble(1)>0&&hint.optDouble(1)<=50)hints.put(new JSONArray().put(hint.optString(0)).put(hint.optDouble(1)).put(Math.max(0,Math.min(30000,hint.optInt(2)))));}clean.put("imageHints",hints);retained.put(key,clean);
            }
        }
        state.put("progress", retained);
        if (state.toString().length() > 1600000) throw new IOException("书架元数据过多，请整理书架");
        if(!prefs.edit().putString("state",state.toString()).commit())throw new IOException("本机记录未能保存，请检查可用空间");
    }

    private static JSONArray cleanHistory(JSONArray incoming)throws Exception{
        JSONArray clean=new JSONArray();if(incoming==null)return clean;
        for(int i=0;i<Math.min(200,incoming.length());i++){JSONObject source=incoming.getJSONObject(i),book=new JSONObject();for(String key:new String[]{"id","source","title","author","cover","tags","summary","updated","seenAt","viewedAt"})if(source.has(key)){Object value=source.get(key);if(value instanceof String&&((String)value).length()>800)value=((String)value).substring(0,800);book.put(key,value);}clean.put(book);}return clean;
    }
    private static JSONObject cleanLearning(JSONObject incoming)throws Exception{
        JSONObject result=new JSONObject(),clean=new JSONObject();JSONObject books=incoming==null?null:incoming.optJSONObject("books");
        if(books!=null){List<String> ids=new ArrayList<>();books.keys().forEachRemaining(ids::add);ids.sort((a,b)->Long.compare(books.optJSONObject(b).optLong("touched"),books.optJSONObject(a).optLong("touched")));
            for(String id:ids.subList(0,Math.min(150,ids.size()))){if(id.length()>100)continue;JSONObject source=books.optJSONObject(id);if(source==null)continue;JSONObject book=new JSONObject().put("lastRead",source.optLong("lastRead")).put("touched",source.optLong("touched"));
                for(String name:new String[]{"views","reads","hours"}){JSONObject values=source.optJSONObject(name),kept=new JSONObject();if(values!=null){List<String> keys=new ArrayList<>();values.keys().forEachRemaining(keys::add);keys.sort(Collections.reverseOrder());for(String key:keys.subList(0,Math.min(name.equals("hours")?73:30,keys.size()))){if(!key.matches(name.equals("hours")?"[0-9]{1,10}":"[0-9]{4}-[0-9]{2}-[0-9]{2}"))continue;if(name.equals("views")){JSONObject v=values.optJSONObject(key);if(v!=null)kept.put(key,new JSONObject().put("weight",Math.max(0,Math.min(.2,v.optDouble("weight")))).put("at",v.optLong("at")));}else kept.put(key,Math.max(0,Math.min(name.equals("hours")?3600:86400,values.optDouble(key))));}}book.put(name,kept);}clean.put(id,book);
            }
        }return result.put("books",clean);
    }

    static void applySafeInsets(View target){
        target.setOnApplyWindowInsetsListener((v,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;}
            else{left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();if(Build.VERSION.SDK_INT>=28&&insets.getDisplayCutout()!=null){DisplayCutout c=insets.getDisplayCutout();left=Math.max(left,c.getSafeInsetLeft());top=Math.max(top,c.getSafeInsetTop());right=Math.max(right,c.getSafeInsetRight());bottom=Math.max(bottom,c.getSafeInsetBottom());}}
            v.setPadding(left,top,right,bottom);
            return insets.consumeSystemWindowInsets();
        });
    }
    private void showLogin(String id){
        if(account==null||loginShowing){reply(id,null,new IOException("暂时无法打开登录窗口"));return;}loginShowing=true;
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);
        TextView note=new TextView(this);note.setText("使用文库账号登录并同步书架。历史与阅读位置保留在本机。");layout.addView(note);
        EditText username=new EditText(this);username.setHint("用户名或邮箱");username.setContentDescription("用户名或邮箱");username.setSingleLine(true);username.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);username.setSaveEnabled(false);layout.addView(username);
        EditText password=new EditText(this);password.setHint("密码");password.setContentDescription("密码");password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setSaveEnabled(false);layout.addView(password);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("登录 Wenku8").setView(layout).setPositiveButton("登录",null).setNegativeButton("取消",null).create();boolean[] completed={false};
        dialog.setOnDismissListener(d->{password.setText("");loginShowing=false;if(!completed[0])reply(id,new JSONObject(),null);});
        dialog.setOnShowListener(d->{Button submit=dialog.getButton(AlertDialog.BUTTON_POSITIVE),cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);submit.setOnClickListener(v->{
            String name=username.getText().toString().trim(),secret=password.getText().toString();if(name.isEmpty()||secret.isEmpty()){note.setText("请输入用户名或邮箱及密码");return;}
            password.setText("");submit.setEnabled(false);cancel.setEnabled(false);username.setEnabled(false);password.setEnabled(false);dialog.setCancelable(false);note.setText("正在登录并读取书架…");
            accounts.execute(()->{try{JSONObject status=account.login(name,secret);runOnUiThread(()->{completed[0]=true;dialog.dismiss();reply(id,status,null);});}catch(Exception failure){runOnUiThread(()->{submit.setEnabled(true);cancel.setEnabled(true);username.setEnabled(true);password.setEnabled(true);dialog.setCancelable(true);note.setText(failure.getMessage());password.requestFocus();});}});
        });});dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    private void pickFiles(String id, boolean folder) {
        if (importRequest != null) { reply(id, null, new IOException("文件选择器已打开")); return; }
        importRequest = id;
        Intent intent = folder ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) : new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, folder ? 22 : 21); } catch (Exception error) { importRequest = null; reply(id, null, error); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode != 21 && requestCode != 22) || importRequest == null) return;
        String id = importRequest; importRequest = null;
        if (resultCode != RESULT_OK || data == null) { reply(id, new JSONObject(), null); return; }
        if(requestCode==22){
            Uri tree=data.getData();if(tree==null){reply(id,new JSONObject(),null);return;}
            try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception error){reply(id,null,new IOException("此文件夹不支持长期读取，请改用导入文件"));return;}
            files.execute(()->{
                JSONArray imported=new JSONArray(),errors=new JSONArray();
                try{
                    ArrayList<JSONObject> entries=new ArrayList<>();String document=DocumentsContract.getTreeDocumentId(tree);
                    String rootName=document.substring(document.lastIndexOf('/')+1);if(rootName.contains(":"))rootName=rootName.substring(rootName.lastIndexOf(':')+1);
                    collectDocuments(tree,document,rootName,"",0,entries);
                    for(JSONObject entry:entries)try{
                        JSONObject book=local.importBook(Uri.parse(entry.getString("uri")),true);
                        local.grouping(book.getString("id"),entry.getString("work"),entry.getString("hint"),tree.toString());imported.put(book);
                    }catch(Exception error){errors.put(error.getMessage());}
                    if(entries.size()>=200)errors.put("本次最多导入 200 个文件，剩余文件可分批导入");
                    reply(id,new JSONObject().put("books",imported).put("errors",errors).put("localBooks",local.all()),null);
                }catch(Exception error){reply(id,null,error);}
            });return;
        }
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        if (data.getClipData() != null) for (int index = 0; index < Math.min(200, data.getClipData().getItemCount()); index++) selected.add(data.getClipData().getItemAt(index).getUri());
        else if (data.getData() != null) selected.add(data.getData());
        files.execute(() -> {
            JSONArray books = new JSONArray(), errors = new JSONArray();
            if(data.getClipData()!=null&&data.getClipData().getItemCount()>200)errors.put("本次最多导入 200 个文件，剩余文件可分批导入");
            for (Uri uri : selected) {
                try {
                    boolean persistent = false;
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); persistent = true; } catch (Exception ignored) {}
                    books.put(local.importBook(uri, persistent));
                } catch (Exception error) { errors.put(error.getMessage()); }
            }
            try { reply(id, new JSONObject().put("books", books).put("errors", errors).put("localBooks", local.all()), null); }
            catch (Exception error) { reply(id, null, error); }
        });
    }

    private void collectDocuments(Uri tree,String document,String work,String hint,int depth,List<JSONObject> out)throws Exception{
        if(depth>8||out.size()>=200)return;
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,document);
        try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(c==null)return;
            while(c.moveToNext()&&out.size()<200){String id=c.getString(0),name=c.getString(1),mime=c.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))collectDocuments(tree,id,depth==0?name:work,hint+"/"+name,depth+1,out);
                else if(name.toLowerCase(Locale.ROOT).matches(".*\\.(epub|txt)$"))out.add(new JSONObject().put("uri",DocumentsContract.buildDocumentUriUsingTree(tree,id).toString()).put("work",work).put("hint",hint));
            }
        }
    }

    private static long size(File file) {
        if (!file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0; File[] files = file.listFiles();
        if (files != null) for (File child : files) total += size(child);
        return total;
    }

    final class ContentClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
            return !HOST.equals(request.getUrl().getHost());
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView web, WebResourceRequest request) {
            try {
                Uri uri = request.getUrl();
                String mime; InputStream stream;
                if (HOST.equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (path == null || path.contains("..")) throw new IOException("无效路径");
                    if (path.startsWith("/local/")) {
                        String[] pieces = path.substring(7).split("/", 2);
                        if (pieces.length != 2) throw new IOException("本地资源不存在");
                        stream = new ByteArrayInputStream(local.resource(pieces[0], pieces[1]));
                        mime = mime(pieces[1]);
                    } else {
                        String asset = path.equals("/") ? "index.html" : path.substring(1);
                        stream = getAssets().open(asset); mime = mime(asset);
                    }
                } else {
                    URL target = new URL(uri.toString());
                    if (!ApiClient.allowedImage(target)) throw new IOException("不允许的资源地址");
                    stream=offline==null?null:offline.image(target.toString());if(stream==null)stream=api.image(target.toString());mime=mime(target.getPath());
                }
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "no-store, max-age=0"); headers.put("Pragma", "no-cache");
                headers.put("Access-Control-Allow-Origin", "https://" + HOST);
                headers.put("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data:; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'");
                return new WebResourceResponse(mime, mime.startsWith("image/") ? null : "UTF-8", 200, "OK", headers, stream);
            } catch (Exception error) {
                return new WebResourceResponse("text/plain", "UTF-8", 404, "Unavailable", Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
            }
        }
    }

    static String mime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".xhtml")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    @Override protected void onStop() {
        super.onStop();
        if (view != null) { view.clearCache(true); }
        if (local != null) local.clearMemory();
    }
    @Override protected void onPause(){if(view!=null)view.evaluateJavascript("window.App && window.App.setForeground(false)",null);super.onPause();}
    @Override protected void onResume(){super.onResume();if(view!=null)view.evaluateJavascript("window.App && window.App.setForeground(true)",null);}
    @Override public void onLowMemory(){super.onLowMemory();api.clearMemory();}
    @Override protected void onDestroy() {
        alive = false;if(readerSystemUi!=null)readerSystemUi.destroy();
        network.shutdownNow(); files.shutdown();accounts.shutdownNow();api.clearMemory();
        if (view != null) { view.removeJavascriptInterface("ReaderNative"); view.destroy(); }
        
        if(offline!=null)offline.close();
        if(tags!=null)tags.close();
        super.onDestroy();
    }
}
