package com.xiahu.reader;

import android.content.*;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Explicit user downloads only. Ordinary reader requests never write into this store. */
final class OfflineCache {
    interface Content {JSONObject chapter(int aid,int cid)throws Exception;InputStream image(String url)throws Exception;}
    private final File root;
    private final SharedPreferences prefs;
    private final Content content;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String,JSONObject> books=new LinkedHashMap<>();
    private final Map<String,File> images=new HashMap<>();
    private long bytes,limit;
    private volatile boolean cancelled,closed;
    private boolean running;
    private int done,total,failed;
    private String message="",jobTitle="";
    private static final Pattern IMAGE=Pattern.compile("<!--image-->\\s*(.*?)\\s*<!--image-->",Pattern.DOTALL);
    OfflineCache(Context c,ApiClient api)throws Exception{this(c,new Content(){public JSONObject chapter(int a,int b)throws Exception{return api.chapter(a,b);}public InputStream image(String u)throws Exception{return api.image(u);}},"offline","offline_settings");}
    OfflineCache(Context c,Content source,String folder,String settings)throws Exception{
        content=source;root=new File(c.getFilesDir(),folder);if(!root.exists()&&!root.mkdirs())throw new IOException("无法建立缓存目录");prefs=c.getSharedPreferences(settings,Context.MODE_PRIVATE);limit=prefs.getInt("limitMiB",200)*1024L*1024L;reload();
    }
    private static byte[] read(InputStream in,int max)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>max)throw new IOException("缓存单个文件过大");out.write(buffer,0,n);}return out.toByteArray();}
    private static long size(File f){if(f.isFile())return f.length();long n=0;File[] children=f.listFiles();if(children!=null)for(File child:children)n+=size(child);return n;}
    private static JSONObject json(File f)throws Exception{try(InputStream in=new AtomicFile(f).openRead()){return new JSONObject(new String(read(in,8*1024*1024),StandardCharsets.UTF_8));}}
    private static String id(int aid)throws IOException{if(aid<=0||aid>1000000)throw new IOException("无效书籍编号");return String.valueOf(aid);}
    private File dir(int aid)throws IOException{return new File(root,id(aid));}
    private static String hash(String url)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private void reload(){
        books.clear();images.clear();File[] dirs=root.listFiles();if(dirs!=null)for(File d:dirs)if(d.isDirectory()&&d.getName().matches("[1-9][0-9]{0,6}"))try{JSONObject book=json(new File(d,"book.json"));if(!book.optString("id").equals(d.getName()))continue;books.put(d.getName(),book);JSONObject urls=book.optJSONObject("images");if(urls!=null){Iterator<String> keys=urls.keys();while(keys.hasNext()){String url=keys.next(),key=urls.optString(url);if(key.matches("[a-f0-9]{64}")){File f=new File(d,key+".img");if(f.isFile())images.put(url,f);}}}}catch(Exception ignored){}
        bytes=size(root);
    }
    private synchronized void write(File file,byte[] data)throws Exception{
        long old=file.isFile()?file.length():0;if(bytes-old+data.length>limit)throw new IOException("已达到缓存空间上限，请清理缓存或调高上限");
        File parent=file.getParentFile();if(!parent.exists()&&!parent.mkdirs())throw new IOException("无法写入缓存目录");AtomicFile target=new AtomicFile(file);FileOutputStream stream=null;
        try{stream=target.startWrite();stream.write(data);target.finishWrite(stream);bytes+=data.length-old;}catch(Exception e){if(stream!=null)target.failWrite(stream);throw e;}
    }
    private void writeJson(File file,JSONObject value)throws Exception{write(file,value.toString().getBytes(StandardCharsets.UTF_8));}
    synchronized JSONObject chapter(int aid,int cid){try{File file=new File(dir(aid),cid+".json");if(cid<=0||!file.isFile())return null;JSONObject value=json(file);if(!"text".equals(value.optString("format"))||!value.has("text"))return null;return value.put("cached",true);}catch(Exception e){return null;}}
    synchronized JSONObject catalog(int aid){JSONObject book=books.get(String.valueOf(aid));return book==null?null:book.optJSONObject("catalog");}
    synchronized void updateCatalog(int aid,JSONObject catalog)throws Exception{JSONObject book=books.get(String.valueOf(aid));if(book!=null){book.put("catalog",catalog);writeJson(new File(dir(aid),"book.json"),book);}}
    synchronized InputStream image(String address)throws Exception{File f=images.get(address.replaceFirst("^http://","https://"));return f!=null&&f.isFile()?new FileInputStream(f):null;}
    synchronized JSONObject status()throws Exception{
        JSONArray list=new JSONArray();for(JSONObject entry:books.values()){
            int aid=entry.getInt("id"),count=0,complete=0;JSONObject imageDone=entry.optJSONObject("imageDone");File[] files=dir(aid).listFiles();if(files!=null)for(File f:files)if(f.getName().matches("[1-9][0-9]*\\.json")&&f.length()>0){count++;if(imageDone!=null&&imageDone.optBoolean(f.getName().replace(".json","")))complete++;}
            JSONObject book=entry.getJSONObject("book");JSONObject item=new JSONObject(book.toString()).put("cachedChapters",count).put("completeChapters",complete).put("totalChapters",entry.getJSONObject("catalog").getJSONArray("chapters").length()).put("cacheBytes",size(dir(aid)));list.put(item);
        }
        return new JSONObject().put("books",list).put("bytes",bytes).put("limitMiB",limit/(1024*1024)).put("running",running).put("done",done).put("total",total).put("failed",failed).put("message",message).put("title",jobTitle);
    }
    synchronized JSONObject limit(int mib)throws Exception{if(mib<16||mib>1024)throw new IOException("缓存上限须在 16–1024 MiB 之间");if(bytes>mib*1024L*1024L)throw new IOException("请先清理部分缓存，再降低空间上限");limit=mib*1024L*1024L;prefs.edit().putInt("limitMiB",mib).apply();return status();}
    synchronized JSONObject start(int aid,JSONObject inputBook,JSONObject catalog,String volume,boolean withImages)throws Exception{
        return start(aid,inputBook,catalog,volume,withImages,0);
    }
    synchronized JSONObject start(int aid,JSONObject inputBook,JSONObject catalog,String volume,boolean withImages,int onlyChapter)throws Exception{
        id(aid);if(running)throw new IOException("已有缓存任务，请等待完成或停止后再开始");if(closed)throw new IOException("缓存任务已关闭");if(!books.containsKey(String.valueOf(aid))&&books.size()>=100)throw new IOException("最多缓存 100 本作品，请先整理");
        JSONArray all=catalog.getJSONArray("chapters");List<JSONObject> selected=new ArrayList<>();Set<Integer> seen=new HashSet<>();for(int i=0;i<all.length();i++){JSONObject c=all.getJSONObject(i);int cid=c.optInt("id");if(cid>0&&seen.add(cid)&&(onlyChapter>0?cid==onlyChapter:volume.isEmpty()||volume.equals(c.optString("volume").isEmpty()?"全部章节":c.optString("volume"))))selected.add(new JSONObject(c.toString()));}
        if(selected.isEmpty()||selected.size()>5000)throw new IOException("请选择有效的章节或分卷");
        JSONObject book=new JSONObject().put("id",String.valueOf(aid)).put("source","online");for(String key:new String[]{"title","author","cover","tags","summary","updated"}){String value=inputBook.optString(key);book.put(key,value.substring(0,Math.min(2000,value.length())));}
        JSONObject entry=books.get(String.valueOf(aid));if(entry==null)entry=new JSONObject().put("images",new JSONObject()).put("imageDone",new JSONObject());entry.put("id",String.valueOf(aid)).put("book",book).put("catalog",catalog);writeJson(new File(dir(aid),"book.json"),entry);books.put(String.valueOf(aid),entry);
        running=true;cancelled=false;done=0;failed=0;total=selected.size();jobTitle=book.optString("title");message="按顺序缓存，退出 App 后任务可能停止";final JSONObject saved=entry;
        worker.execute(()->download(aid,saved,selected,withImages));return status();
    }
    private void checkCancelled()throws InterruptedException{if(cancelled||closed||Thread.currentThread().isInterrupted())throw new InterruptedException();}
    private void cacheImage(int aid,JSONObject entry,String address)throws Exception{
        String url=address.trim().replaceFirst("^http://","https://");if(!ApiClient.allowedImage(new URL(url)))throw new IOException("插图地址不可缓存");
        String key=hash(url);File file=new File(dir(aid),key+".img");File shared;
        synchronized(this){if(file.isFile()&&entry.getJSONObject("images").has(url))return;shared=images.get(url);}
        byte[] data;if(shared!=null&&shared.isFile()){try(InputStream in=new FileInputStream(shared)){data=read(in,8*1024*1024);}}else try(InputStream in=content.image(url)){data=read(in,8*1024*1024);}checkCancelled();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,options);if(options.outWidth<=0||options.outHeight<=0)throw new IOException("来源未返回有效插图");
        write(file,data);
        synchronized(this){entry.getJSONObject("images").put(url,key);writeJson(new File(dir(aid),"book.json"),entry);images.put(url,file);}
        Thread.sleep(350);
    }
    private void download(int aid,JSONObject entry,List<JSONObject> selected,boolean withImages){
        int consecutive=0;boolean incomplete=false;
        try{
            if(withImages&&!entry.getJSONObject("book").optString("cover").isEmpty())try{cacheImage(aid,entry,entry.getJSONObject("book").getString("cover"));}catch(InterruptedException e){throw e;}catch(Exception ignored){incomplete=true;}
            for(JSONObject info:selected){
                checkCancelled();int cid=info.getInt("id");
                try{
                    JSONObject value=chapter(aid,cid);if(value==null){value=content.chapter(aid,cid);checkCancelled();value.remove("cached");value.put("imagesComplete",false);writeJson(new File(dir(aid),cid+".json"),value);Thread.sleep(500);}
                    if(withImages&&!value.optBoolean("imagesComplete")){Matcher matcher=IMAGE.matcher(value.getString("text"));LinkedHashSet<String> urls=new LinkedHashSet<>();while(matcher.find())urls.add(matcher.group(1));if(urls.size()>150)throw new IOException("单章插图过多，请在线阅读本章");for(String url:urls){checkCancelled();cacheImage(aid,entry,url);}value.remove("cached");value.put("imagesComplete",true);writeJson(new File(dir(aid),cid+".json"),value);synchronized(this){entry.getJSONObject("imageDone").put(String.valueOf(cid),true);writeJson(new File(dir(aid),"book.json"),entry);}}
                    synchronized(this){done++;message="已处理 "+done+" / "+total+" 章";}consecutive=0;
                }catch(InterruptedException e){throw e;}catch(Exception e){synchronized(this){failed++;done++;message="部分内容未完成："+e.getMessage();}incomplete=true;if(++consecutive>=3||String.valueOf(e.getMessage()).contains("上限"))break;Thread.sleep(1000);}
            }
            synchronized(this){if(done==total&&!incomplete&&failed==0)message="缓存完成";else if(done==total&&failed==0)message="正文已缓存，封面暂未完成";else if(!message.contains("未完成"))message="部分缓存未完成，可再次选择同一范围继续";}
        }catch(InterruptedException e){synchronized(this){message="已停止，完成的缓存已保留；再次选择相同范围可继续";}}catch(Exception e){synchronized(this){message="缓存未完成："+e.getMessage();}}
        finally{synchronized(this){running=false;}}
    }
    synchronized JSONObject cancel()throws Exception{cancelled=true;message=running?"正在停止，当前请求结束后停止":"缓存任务已结束";return status();}
    private static void delete(File f)throws IOException{File[] files=f.listFiles();if(files!=null)for(File child:files)delete(child);if(f.exists()&&!f.delete())throw new IOException("部分缓存未能删除，请重试");}
    synchronized JSONObject remove(int aid)throws Exception{if(running)throw new IOException("请先停止缓存任务，等待当前请求结束");if(aid==0){File[] files=root.listFiles();if(files!=null)for(File f:files)delete(f);}else delete(dir(aid));reload();message="缓存已清理，收藏、历史和本地原文件保留";return status();}
    void close(){closed=true;cancelled=true;worker.shutdownNow();}
}
