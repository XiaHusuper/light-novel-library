package com.xiahu.reader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Local originals remain user-managed; EPUB entries are read without extraction. */
final class LocalBooks {
    private final Context context;
    private final SharedPreferences prefs;
    private String memoryBook = "";
    private String memoryText;

    LocalBooks(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("local_library", Context.MODE_PRIVATE);
    }

    synchronized JSONArray all() throws Exception {
        JSONArray books = new JSONArray(prefs.getString("books", "[]")); boolean changed = false;
        for (int i=0;i<books.length();i++) {
            JSONObject old=books.getJSONObject(i); if(old.optInt("parserVersion")>=2)continue;
            try {
                Uri uri=Uri.parse(old.getString("uri")); String filename=old.getString("filename");
                JSONObject fresh="epub".equals(old.getString("kind"))?importEpub(old.getString("id"),uri,filename):importText(old.getString("id"),uri,filename);
                for(String key:new String[]{"chapters","spine","navigation","tags","summary","workTitle"})if(fresh.has(key))old.put(key,fresh.get(key));
            } catch(Exception error) { old.put("indexWarning", "原文件暂时不可用，可重新导入"); }
            old.put("parserVersion",2);changed=true;
        }
        if(changed)prefs.edit().putString("books",books.toString()).apply(); return books;
    }

    synchronized JSONObject find(String id) throws Exception {
        JSONArray books = all();
        for (int index = 0; index < books.length(); index++) {
            JSONObject book = books.getJSONObject(index);
            if (id.equals(book.optString("id"))) return book;
        }
        throw new IOException("本地书籍已移除，请重新导入");
    }

    synchronized void grouping(String id,String work,String hint,String tree)throws Exception{
        JSONArray books=all();for(int i=0;i<books.length();i++){
            JSONObject book=books.getJSONObject(i);if(!id.equals(book.optString("id")))continue;
            book.put("workTitle",work).put("treeUri",tree);String edition=LibraryRules.edition(hint);
            if(!edition.equals("main")){JSONArray chapters=book.getJSONArray("chapters");for(int j=0;j<chapters.length();j++)chapters.getJSONObject(j).put("edition",edition);}
        }prefs.edit().putString("books",books.toString()).apply();
    }

    synchronized JSONObject orders()throws Exception{return new JSONObject(prefs.getString("orders","{}"));}
    synchronized JSONObject order(JSONObject input)throws Exception{
        String id=input.optString("id");if(!id.matches("work_[a-z0-9]{1,16}"))throw new IOException("无效的本地作品");JSONArray requested=input.optJSONArray("fileIds");if(requested==null||requested.length()<1||requested.length()>200)throw new IOException("请选择有效的本地作品");
        Set<String> files=new LinkedHashSet<>();for(int i=0;i<requested.length();i++)files.add(requested.getString(i));if(files.size()!=requested.length())throw new IOException("本地文件重复");
        Set<String> allowed=new HashSet<>(),found=new HashSet<>();JSONArray all=all();for(int i=0;i<all.length();i++){JSONObject book=all.getJSONObject(i);String file=book.getString("id");if(!files.contains(file))continue;found.add(file);JSONArray chapters=book.getJSONArray("chapters");for(int j=0;j<chapters.length();j++)allowed.add(file+":"+chapters.getJSONObject(j).getString("id"));}
        if(!found.equals(files))throw new IOException("本地文件已变化，请重新打开书籍");JSONObject saved=orders();
        if(input.optBoolean("reset"))saved.remove(id);else{JSONArray keys=input.optJSONArray("keys");if(keys==null||keys.length()!=allowed.size()||keys.length()>20000)throw new IOException("目录不完整，请重新打开后调整");Set<String> seen=new HashSet<>();JSONArray clean=new JSONArray();for(int i=0;i<keys.length();i++){String key=keys.getString(i);if(!allowed.contains(key)||!seen.add(key)||key.length()>200)throw new IOException("目录包含无效或重复章节");clean.put(key);}saved.put(id,new JSONObject().put("files",requested).put("keys",clean).put("updatedAt",System.currentTimeMillis()));}
        if(saved.length()>100||saved.toString().length()>1024*1024)throw new IOException("手动目录记录过多，请恢复部分作品的自动顺序");if(!prefs.edit().putString("orders",saved.toString()).commit())throw new IOException("目录顺序保存失败，请检查可用空间");return new JSONObject().put("orders",saved).put("localBooks",all);
    }
    private synchronized void pruneOrders(JSONArray remaining)throws Exception{
        Set<String> ids=new HashSet<>();for(int i=0;i<remaining.length();i++)ids.add(remaining.getJSONObject(i).getString("id"));JSONObject values=orders();Iterator<String> names=values.keys();List<String> gone=new ArrayList<>();while(names.hasNext()){String name=names.next();JSONObject value=values.getJSONObject(name);JSONArray files=value.optJSONArray("files");boolean exists=false;if(files!=null)for(int i=0;i<files.length();i++)if(ids.contains(files.getString(i)))exists=true;if(!exists)gone.add(name);}for(String name:gone)values.remove(name);prefs.edit().putString("orders",values.toString()).apply();
    }

    synchronized void remove(String id) throws Exception {
        JSONArray previous = all(), kept = new JSONArray(); Set<String> removedTrees=new HashSet<>();
        for (int index = 0; index < previous.length(); index++) {
            JSONObject book = previous.getJSONObject(index);
            if(id.equals(book.optString("id"))&&book.has("treeUri"))removedTrees.add(book.getString("treeUri"));
            if (!id.equals(book.optString("id"))) kept.put(book);
            else if (book.optBoolean("ownedCopy")) {
                File file = new File(Uri.parse(book.getString("uri")).getPath());
                if (file.getCanonicalPath().startsWith(new File(context.getFilesDir(), "imports").getCanonicalPath() + File.separator)) file.delete();
            } else {
                try { context.getContentResolver().releasePersistableUriPermission(Uri.parse(book.getString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            }
        }
        prefs.edit().putString("books", kept.toString()).apply();pruneOrders(kept);
        for(String tree:removedTrees){boolean used=false;for(int i=0;i<kept.length();i++)if(tree.equals(kept.getJSONObject(i).optString("treeUri")))used=true;if(!used)try{context.getContentResolver().releasePersistableUriPermission(Uri.parse(tree),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}
        if (id.equals(memoryBook)) { memoryBook = ""; memoryText = null; }
    }

    JSONObject importBook(Uri original, boolean persistentPermission) throws Exception {
        String documentKey=documentKey(original);
        synchronized (this) {
            JSONArray existing = all();
            for (int index = 0; index < existing.length(); index++) {
                JSONObject item = existing.getJSONObject(index);
                if (documentKey.equals(item.optString("documentKey",documentKey(Uri.parse(item.optString("originalUri")))))) return item;
            }
        }
        String filename = name(original), lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".epub") && !lower.endsWith(".txt")) throw new IOException("请选择 EPUB 或 TXT 文件");
        String id = "local_" + UUID.randomUUID().toString().replace("-", "");
        Uri uri = original;
        boolean owned = false;
        if (!persistentPermission) {
            File directory = new File(context.getFilesDir(), "imports");
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法建立本地导入目录");
            File file = new File(directory, id + (lower.endsWith(".epub") ? ".epub" : ".txt"));
            try (InputStream input = context.getContentResolver().openInputStream(original); OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[16384]; int count; long total = 0;
                while ((count = input.read(buffer)) >= 0) {
                    total += count;
                    if (total > 256L * 1024 * 1024) throw new IOException("本地文件超过 256 MB 导入限制");
                    output.write(buffer, 0, count);
                }
            } catch (Exception error) { file.delete(); throw error; }
            uri = Uri.fromFile(file); owned = true;
        }
        JSONObject book;
        try {
            book = lower.endsWith(".epub") ? importEpub(id, uri, filename) : importText(id, uri, filename);
        } catch (Exception error) {
            if (owned) new File(uri.getPath()).delete();
            throw error;
        }
        book.put("parserVersion", 2).put("source", "local").put("uri", uri.toString()).put("filename", filename).put("ownedCopy", owned);
        synchronized (this) {
            JSONArray books = all();
            for (int index = 0; index < books.length(); index++) {
                JSONObject previous = books.getJSONObject(index);
                if (documentKey.equals(previous.optString("documentKey",documentKey(Uri.parse(previous.optString("originalUri")))))) {if(owned)new File(uri.getPath()).delete();return previous;}
            }
            book.put("originalUri", original.toString()).put("documentKey",documentKey);
            books.put(book);
            prefs.edit().putString("books", books.toString()).apply();
        }
        return book;
    }

    static String documentKey(Uri uri){try{return uri.getAuthority()+":"+DocumentsContract.getDocumentId(uri);}catch(Exception ignored){return uri.toString();}}

    private String name(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment() == null ? "本地文件" : uri.getLastPathSegment();
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (Exception ignored) { return new String(bytes, Charset.forName("GB18030")); }
    }

    private synchronized String text(String id, Uri uri) throws Exception {
        if (id.equals(memoryBook) && memoryText != null) return memoryText;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            memoryText = decode(ApiClient.readLimited(input, 24 * 1024 * 1024)).replace("\r\n", "\n").replace('\r','\n');
            memoryBook = id;
            return memoryText;
        }
    }

    private JSONObject importText(String id, Uri uri, String filename) throws Exception {
        String body = text(id, uri);
        if(body.trim().isEmpty()||body.indexOf('\0')>=0)throw new IOException("TXT 文件没有可阅读的文字");
        JSONArray chapters = LibraryRules.textChapters(body, filename);
        String title = filename.replaceFirst("(?i)\\.txt$", "");
        return new JSONObject().put("id", id).put("title", title).put("author", "本地书籍")
            .put("workTitle", LibraryRules.workTitle(title, filename)).put("kind", "txt").put("chapters", chapters).put("cover", "");
    }

    private JSONObject importEpub(String id, Uri uri, String filename) throws Exception {
        return EpubIndex.parse(id, filename, (path, limit) -> zipBytes(uri, path, limit));
    }

    static String first(Document document, String name, String fallback) {
        NodeList elements = document.getElementsByTagNameNS("*", name);
        return elements.getLength() == 0 ? fallback : elements.item(0).getTextContent().trim();
    }

    static Document localXml(String text) throws Exception {
        if (text.toUpperCase(Locale.ROOT).contains("<!ENTITY")) throw new IOException("本地文档不支持实体声明");
        return ApiClient.xml(text.replaceAll("(?is)<!DOCTYPE[^>]*>", ""));
    }

    static String resolve(String base, String relative) throws Exception {
        String decoded = URLDecoder.decode(relative.replace("+", "%2B"), "UTF-8").split("[?#]", 2)[0];
        if (decoded.contains(":") || decoded.startsWith("//")) throw new IOException("不支持外部 EPUB 资源");
        String combined = (base.contains("/") ? base.substring(0, base.lastIndexOf('/') + 1) : "") + decoded;
        ArrayDeque<String> components = new ArrayDeque<>();
        for (String part : combined.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (components.isEmpty()) throw new IOException("无效的 EPUB 资源路径"); components.removeLast(); }
            else components.addLast(part);
        }
        return String.join("/", components);
    }

    static String resourceUrl(String id, String path) { return "https://appassets.androidplatform.net/local/" + id + "/" + Uri.encode(path, "/"); }

    JSONObject chapter(String id, int index) throws Exception {
        JSONObject book = find(id);
        JSONObject chapter = book.getJSONArray("chapters").getJSONObject(index);
        Uri uri = Uri.parse(book.getString("uri"));
        if ("txt".equals(book.getString("kind"))) {
            String body = text(id, uri);
            return new JSONObject().put("format", "text").put("text", body.substring(chapter.getInt("start"), Math.min(body.length(), chapter.getInt("end"))));
        }
        if (book.has("spine") && chapter.has("target")) return new JSONObject().put("format", "epub").put("bookId", id)
            .put("parts", styledParts(uri,EpubIndex.parts(book,index,(path,limit)->zipBytes(uri,path,limit))));
        String entry = chapter.getString("entry");
        return withStyles(uri,new JSONObject().put("format","epub").put("html",decode(zipBytes(uri,entry,4*1024*1024))).put("entry",entry).put("bookId",id));
    }

    private JSONArray styledParts(Uri uri,JSONArray parts)throws Exception{for(int i=0;i<parts.length();i++)withStyles(uri,parts.getJSONObject(i));return parts;}
    private JSONObject withStyles(Uri uri,JSONObject part)throws Exception{
        String html=part.optString("html"),base=part.optString("entry");StringBuilder css=new StringBuilder();Matcher links=Pattern.compile("<link\\b[^>]*>",Pattern.CASE_INSENSITIVE).matcher(html);int count=0;
        while(links.find()&&count++<16){String tag=links.group();Matcher rel=Pattern.compile("\\brel\\s*=\\s*['\"]([^'\"]+)['\"]",Pattern.CASE_INSENSITIVE).matcher(tag),href=Pattern.compile("\\bhref\\s*=\\s*['\"]([^'\"]+)['\"]",Pattern.CASE_INSENSITIVE).matcher(tag);if(!rel.find()||!rel.group(1).toLowerCase(Locale.ROOT).contains("stylesheet")||!href.find())continue;
            try{java.net.URI origin=new java.net.URI("https","local.invalid","/"+base,null),resolved=origin.resolve(href.group(1).replace("&amp;","&")).normalize();if(!"https".equals(resolved.getScheme())||!"local.invalid".equals(resolved.getHost()))continue;String path=resolved.getPath().substring(1);if(path.contains("..")||path.contains(":"))continue;String value=decode(zipBytes(uri,path,64*1024));if(css.length()+value.length()>128*1024)break;css.append(value).append('\n');}catch(Exception ignored){}
        }
        return part.put("styles",css.toString());
    }

    byte[] resource(String id, String path) throws Exception {
        if (path.startsWith("/") || path.contains("..") || path.contains(":")) throw new IOException("无效的资源路径");
        JSONObject book = find(id);
        return zipBytes(Uri.parse(book.getString("uri")), path, 8 * 1024 * 1024);
    }

    private byte[] zipBytes(Uri uri, String entryName, int limit) throws Exception {
        if (entryName.startsWith("/") || entryName.contains("..") || entryName.contains(":")) throw new IOException("无效的 EPUB 路径");
        // Local SAF providers normally expose seekable descriptors: ZipFile is fast and avoids copying.
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null) throw new IOException("原文件不可用，请重新关联文件");
            try (ZipFile zip = new ZipFile("/proc/self/fd/" + descriptor.getFd())) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) throw new FileNotFoundException("EPUB 资源不存在");
                try (InputStream input = zip.getInputStream(entry)) { return ApiClient.readLimited(input, limit); }
            }
        } catch (ZipException | FileNotFoundException error) {
            try (InputStream source = context.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(source)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) if (entryName.equals(entry.getName())) return ApiClient.readLimited(zip, limit);
                throw new FileNotFoundException("EPUB 资源不存在或原文件已移动");
            }
        }
    }

    synchronized void clearMemory() { memoryBook = ""; memoryText = null; }
}
