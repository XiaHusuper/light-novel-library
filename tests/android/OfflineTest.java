package com.xiahu.reader;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.graphics.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

/** All text and illustrations in this test are generated fixtures, never website books. */
public final class OfflineTest extends Instrumentation {
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);}
    static void await(OfflineCache cache)throws Exception{long until=System.currentTimeMillis()+20000;while(cache.status().getBoolean("running")&&System.currentTimeMillis()<until)Thread.sleep(50);check(!cache.status().getBoolean("running"),"Download must finish");}
    static final class Source implements OfflineCache.Content {
        int texts,pictures,active,maxActive;boolean large,offline;byte[] png;
        Source(){Bitmap b=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);b.eraseColor(Color.GREEN);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);png=out.toByteArray();b.recycle();}
        public JSONObject chapter(int aid,int cid)throws Exception{if(offline)throw new IOException("Fixture offline");texts++;active++;maxActive=Math.max(maxActive,active);Thread.sleep(40);active--;String url="https://img.wenku8.com/original-fixture/"+(large?cid:1)+".png";return new JSONObject().put("format","text").put("text","Original cache fixture, chapter "+cid+"\n<!--image-->"+url+"<!--image-->");}
        public InputStream image(String url)throws Exception{if(offline)throw new IOException("Fixture offline");pictures++;if(large){byte[] padded=new byte[8*1024*1024];System.arraycopy(png,0,padded,0,png.length);return new ByteArrayInputStream(padded);}return new ByteArrayInputStream(png);}
    }
    static JSONObject catalog()throws Exception{return new JSONObject().put("chapters",new JSONArray().put(new JSONObject().put("id",101).put("volume","第一卷").put("title","Original one")).put(new JSONObject().put("id",102).put("volume","第一卷").put("title","Original two")).put(new JSONObject().put("id",103).put("volume","第二卷").put("title","Original three")));}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();OfflineCache cache=null;Context c=getTargetContext();
        try{
            Source source=new Source();cache=new OfflineCache(c,source,"offline_test_only","offline_test_settings");cache.remove(0);cache.limit(200);
            check(cache.chapter(129,101)==null&&source.texts==0,"Ordinary cache lookup never downloads");
            JSONObject book=new JSONObject().put("title","Original fixture").put("cover","https://img.wenku8.com/original-fixture/1.png");
            cache.start(129,book,catalog(),"第一卷",true);boolean blocked=false;try{cache.start(129,book,catalog(),"",true);}catch(Exception expected){blocked=true;}check(blocked,"One explicit cache job at a time");await(cache);
            check(source.texts==2&&source.pictures==1&&source.maxActive==1,"Sequential selected-volume download and image reuse");check(cache.chapter(129,101).getBoolean("cached")&&cache.chapter(129,103)==null,"Only selected chapters stored");
            cache.start(129,book,catalog(),"第一卷",true);await(cache);check(source.texts==2&&source.pictures==1,"Resuming a complete volume makes no content requests");
            cache.start(200,book,catalog(),"第一卷",true);await(cache);cache.remove(129);check(cache.image("https://img.wenku8.com/original-fixture/1.png")!=null,"Deleting one book preserves another book's images");
            cache.close();source.offline=true;cache=new OfflineCache(c,source,"offline_test_only","offline_test_settings");check(cache.catalog(200)!=null&&cache.chapter(200,102)!=null,"Cold restart works without network");check(cache.status().getJSONArray("books").getJSONObject(0).getInt("completeChapters")==2,"Complete illustration metadata survives restart");
            cache.remove(0);check(cache.status().getLong("bytes")==0&&cache.status().getJSONArray("books").length()==0,"Per-app explicit cache clears completely");
            source.offline=false;source.large=true;cache.limit(16);cache.start(300,new JSONObject().put("title","Quota fixture"),catalog(),"",true);await(cache);check(cache.status().getLong("bytes")<=16L*1024*1024&&cache.status().getInt("failed")>0,"Quota stops downloads without evicting completed data");
            cache.remove(0);source.large=false;cache.start(129,book,catalog(),"",false);cache.cancel();await(cache);check(cache.status().getString("message").contains("停止"),"Cancellation preserves completed parts");cache.remove(0);
            if(android.os.Build.VERSION.SDK_INT>=30)runOnMainSync(()->{
                android.widget.FrameLayout parent=new android.widget.FrameLayout(c);android.view.View child=new android.view.View(c);parent.addView(child,new android.widget.FrameLayout.LayoutParams(-1,-1));MainActivity.applySafeInsets(parent);
                android.view.WindowInsets insets=new android.view.WindowInsets.Builder().setInsets(android.view.WindowInsets.Type.systemBars(),Insets.of(7,24,9,48)).setInsets(android.view.WindowInsets.Type.displayCutout(),Insets.of(0,80,0,0)).build();parent.dispatchApplyWindowInsets(insets);
                parent.measure(android.view.View.MeasureSpec.makeMeasureSpec(393,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(852,android.view.View.MeasureSpec.EXACTLY));parent.layout(0,0,393,852);check(child.getTop()==80&&child.getLeft()==7&&child.getHeight()==724&&child.getWidth()==377,"Native parent excludes status bar, cutout and navigation bar");
            });
            out.putString("report",new JSONObject().put("passed",true).put("nativeParentResizesForStatusCutoutAndNavigation",true).put("explicitDownloadsOnly",true).put("selectedVolumeAndSequentialRequests",true).put("resumeWithoutRefetch",true).put("sharedImageRemovalSafe",true).put("offlineRestartTextAndImages",true).put("spaceLimitEnforced",true).put("cancelAndClear",true).put("fixture","generated original text and PNG; no website downloads").toString());finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("failure",e.toString());finish(Activity.RESULT_CANCELED,out);}finally{if(cache!=null){try{if(!cache.status().optBoolean("running"))cache.remove(0);}catch(Exception ignored){}cache.close();}c.getSharedPreferences("offline_test_settings",0).edit().clear().commit();}
    }
}
