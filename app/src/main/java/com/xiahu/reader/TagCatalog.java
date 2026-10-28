package com.xiahu.reader;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Resumable, bounded catalogue metadata index. Never fetches novel bodies or illustrations. */
final class TagCatalog {
    interface Source {JSONObject page(int number)throws Exception;}
    interface QuickSource {JSONObject search(String tag)throws Exception;}
    private final Source source;
    private final QuickSource quickSource;
    private final Set<String> quickTerms=new LinkedHashSet<>();
    private int quickRunning,quickFinished,generation;
    private final AtomicFile file;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(),fetchers=Executors.newFixedThreadPool(2);
    private final Map<String,JSONObject> books=new LinkedHashMap<>();
    private int next=1,pages;
    private long updatedAt;
    private boolean running;
    private volatile boolean stopped,closed;
    private String error="";
    TagCatalog(Context context,ApiClient api){this(context,n->api.list("","postdate",n),tag->api.list(tag,"postdate",1),"tag_catalog.json");}
    TagCatalog(Context context,Source source,String filename){this(context,source,null,filename);}
    TagCatalog(Context context,Source source,QuickSource quickSource,String filename){
        this.source=source;this.quickSource=quickSource;file=new AtomicFile(new File(context.getFilesDir(),filename));
        try(InputStream in=file.openRead()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>4*1024*1024)throw new IOException();out.write(buffer,0,n);}
            JSONObject value=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));if(value.optInt("version")!=1)throw new IOException();next=Math.max(1,value.optInt("next",1));pages=Math.max(0,value.optInt("pages"));updatedAt=value.optLong("updatedAt");JSONArray list=value.getJSONArray("books");if(list.length()>10000||pages>2000)throw new IOException();for(int i=0;i<list.length();i++)put(list.getJSONObject(i));
        }catch(Exception ignored){books.clear();next=1;pages=0;updatedAt=0;}
    }
    private void put(JSONObject value)throws Exception{
        String id=value.optString("id");if(!id.matches("[1-9][0-9]{0,6}")||Integer.parseInt(id)>1000000||value.optString("title").isEmpty())throw new IOException("书目编号或标题不完整");
        JSONObject clean=new JSONObject().put("id",id).put("source","online");for(String key:new String[]{"title","author","tags","status","updated","totalHits","recommendations","favorites"}){String text=value.optString(key);clean.put(key,text.substring(0,Math.min(300,text.length())));}books.put(id,clean);if(books.size()>10000)throw new IOException("书目索引超过本版上限");
    }
    private boolean complete(){return pages>0&&next>pages;}
    private synchronized void save()throws Exception{
        JSONObject value=new JSONObject().put("version",1).put("next",next).put("pages",pages).put("updatedAt",updatedAt).put("books",new JSONArray(books.values()));byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>4*1024*1024)throw new IOException("书目索引超过 4 MiB 上限");FileOutputStream out=null;try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
    }
    synchronized JSONObject status()throws Exception{return new JSONObject().put("running",running).put("complete",complete()).put("scannedPages",next-1).put("totalPages",pages).put("indexedBooks",books.size()).put("updatedAt",updatedAt).put("error",error).put("quickRunning",quickRunning>0).put("quickFinished",quickFinished);}
    synchronized void start(boolean refresh)throws Exception{
        if(running||closed)return;
        if(refresh||(complete()&&System.currentTimeMillis()-updatedAt>7L*86400000)){books.clear();next=1;pages=0;updatedAt=0;generation++;quickTerms.clear();quickFinished=0;save();}
        if(complete())return;running=true;stopped=false;error="";worker.execute(this::scan);
    }
    private void quick(Set<String> terms){
        if(quickSource==null||closed||complete())return;int selected=0;final int epoch=generation;
        for(String tag:terms){if(selected++>=3)break;if(quickTerms.contains(tag)||quickTerms.size()>=100)continue;quickTerms.add(tag);quickRunning++;
            fetchers.submit(()->{try{JSONObject result=quickSource.search(tag);synchronized(this){if(epoch!=generation||closed)return;JSONArray incoming=result.getJSONArray("books");for(int i=0;i<Math.min(100,incoming.length());i++)put(incoming.getJSONObject(i));quickFinished++;save();}}catch(Exception ignored){/* Candidate search is optional; catalogue completeness is unchanged. */}finally{synchronized(this){quickRunning--;}}});
        }
    }
    private void scan(){
        try{
            while(!stopped&&!closed){
                int first,last;synchronized(this){if(complete())break;first=next;last=pages==0?first:Math.min(pages,first+1);}
                List<Future<JSONObject>> requests=new ArrayList<>();for(int page=first;page<=last;page++){final int p=page;requests.add(fetchers.submit(()->source.page(p)));}
                for(int i=0;i<requests.size();i++){
                    JSONObject result=requests.get(i).get();if(stopped||closed)break;
                    synchronized(this){int count=result.optInt("pages");if(count<=0||count>2000)throw new IOException("来源未返回有效书目分页");pages=count;JSONArray incoming=result.getJSONArray("books");for(int j=0;j<incoming.length();j++)put(incoming.getJSONObject(j));next=first+i+1;if(complete())updatedAt=System.currentTimeMillis();if(next%8==0||complete())save();}
                }
                if(!stopped&&!closed)Thread.sleep(400);
            }
        }catch(Exception failure){synchronized(this){Throwable reason=failure instanceof ExecutionException?failure.getCause():failure;error=reason==null?"索引读取中断":String.valueOf(reason.getMessage());if(error.length()>160)error="索引读取中断，请继续检索";}}
        finally{synchronized(this){running=false;try{save();}catch(Exception e){error="书目索引未能保存，当前结果仍可查看";}}}
    }
    synchronized JSONObject pause()throws Exception{stopped=true;return status();}
    static String normalized(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).trim().replaceFirst("^#+","").toLowerCase(Locale.ROOT);}
    synchronized JSONObject query(JSONArray tags,String mode,String query,int limit,boolean start,boolean refresh)throws Exception{
        if(tags==null||tags.length()==0||tags.length()>60)throw new IOException("请选择标签");LinkedHashSet<String> wanted=new LinkedHashSet<>();for(int i=0;i<tags.length();i++){String term=normalized(tags.getString(i));if(term.isEmpty()||term.length()>40)throw new IOException("标签无效");wanted.add(term);}if(!mode.equals("all")&&!mode.equals("any"))throw new IOException("筛选方式无效");if(start){if((refresh||(complete()&&System.currentTimeMillis()-updatedAt>7L*86400000))&&!running){books.clear();next=1;pages=0;updatedAt=0;generation++;quickTerms.clear();quickFinished=0;save();}quick(wanted);start(false);}
        final String needle=normalized(query);List<JSONObject> hits=new ArrayList<>();
        for(JSONObject book:books.values()){
            if(!needle.isEmpty()&&!normalized(book.optString("title")+" "+book.optString("author")).contains(needle))continue;
            Set<String> terms=new HashSet<>();for(String t:book.optString("tags").split("[\\s,，、;；/]+"))if(!t.isEmpty())terms.add(normalized(t));boolean match=mode.equals("all")?terms.containsAll(wanted):!Collections.disjoint(terms,wanted);if(match)hits.add(book);
        }
        hits.sort((a,b)->{int date=b.optString("updated").compareTo(a.optString("updated"));return date!=0?date:Integer.compare(b.optInt("id"),a.optInt("id"));});
        JSONArray result=new JSONArray();for(JSONObject raw:hits.subList(0,Math.min(Math.max(1,Math.min(10000,limit)),hits.size()))){JSONObject b=new JSONObject(raw.toString());int aid=b.getInt("id");b.put("cover","https://img.wenku8.com/image/"+(aid/1000)+"/"+aid+"/"+aid+"s.jpg");result.put(b);}
        return status().put("books",result).put("totalMatches",hits.size());
    }
    void close(){closed=true;stopped=true;worker.shutdownNow();fetchers.shutdownNow();}
}
