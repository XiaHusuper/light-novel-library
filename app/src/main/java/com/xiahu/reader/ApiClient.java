package com.xiahu.reader;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mewx.wenku8.api.TokenManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

/** Network requests have no disk cache and never persist chapter/image bodies. */
final class ApiClient {
    static final List<String> SORTS=Arrays.asList("lastupdate","allvisit","allvote","monthvisit","monthvote","weekvisit","weekvote","dayvisit","dayvote","postdate","goodnum","size","fullflag");
    static final String ENDPOINT = "https://wenku8-relay.mewx.org/";
    private final Semaphore apiSlots = new Semaphore(3, true);
    private final Semaphore imageSlots = new Semaphore(2, true);
    private final Semaphore coverSlots = new Semaphore(5, true);
    private final LinkedHashMap<String,byte[]> covers=new LinkedHashMap<>(64,.75f,true);
    private int coverBytes;
    static final class Exchange {final String body;final List<String> cookies;Exchange(String b,List<String> c){body=b;cookies=c;}}

    static String encoded(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }

    String request(String action) throws Exception {
        return exchange(action, "").body;
    }

    Exchange exchange(String action,String cookie) throws Exception {
        apiSlots.acquire();
        HttpURLConnection connection = null;
        try {
            String version = TokenManager.getMagicVersionCpp("1.30");
            String form = "appver=" + encoded(version) + "&timetoken=" + System.currentTimeMillis()
                + "&request=" + encoded(Base64.encodeToString(action.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            configure(connection);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            if(!cookie.isEmpty())connection.setRequestProperty("Cookie",cookie);
            byte[] payload = form.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            checkStatus(connection);
            try (InputStream input = decodedStream(connection)) {
                String text = new String(readLimited(input, 3 * 1024 * 1024), StandardCharsets.UTF_8);
                if (text.trim().isEmpty() || text.contains("<h1>Welcome</h1>")) throw new IOException("接口没有返回书籍内容，请稍后重试");
                List<String> cookies=new ArrayList<>();
                for(Map.Entry<String,List<String>> header:connection.getHeaderFields().entrySet())if("Set-Cookie".equalsIgnoreCase(header.getKey()))cookies.addAll(header.getValue());
                return new Exchange(text,cookies);
            }
        } finally {
            if (connection != null) connection.disconnect();
            apiSlots.release();
        }
    }

    static void configure(HttpURLConnection connection) {
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(16000);
        connection.setRequestProperty("User-Agent", "Android");
        connection.setRequestProperty("Cache-Control", "no-cache, no-store");
        connection.setRequestProperty("Accept-Encoding", "gzip");
    }

    static void checkStatus(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status == 429) throw new IOException("请求较频繁，请稍后重试");
        if (status == 401 || status == 403) throw new IOException("来源暂时不允许读取这项内容");
        if (status != 200) throw new IOException("来源响应异常（" + status + "），请稍后重试");
    }

    static InputStream decodedStream(HttpURLConnection connection) throws IOException {
        InputStream input = connection.getInputStream();
        return "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(input) : input;
    }

    static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maxBytes) throw new IOException("文件超过本次读取大小限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static Document xml(String text) throws Exception {
        if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) throw new IOException("不支持含外部实体的文档");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    static JSONObject book(Element container, int fallbackId) throws Exception {
        int id = fallbackId;
        if (container.hasAttribute("aid")) id = Integer.parseInt(container.getAttribute("aid"));
        JSONObject book = new JSONObject().put("id", String.valueOf(id)).put("source", "online");
        NodeList data = container.getElementsByTagName("data");
        for (int index = 0; index < data.getLength(); index++) {
            Element field = (Element) data.item(index);
            String name = field.getAttribute("name");
            String value = field.hasAttribute("value") ? field.getAttribute("value") : field.getTextContent().trim();
            switch (name) {
                case "Title": book.put("title", value); if (field.hasAttribute("aid")) id = Integer.parseInt(field.getAttribute("aid")); break;
                case "Author": book.put("author", value); break;
                case "BookStatus": book.put("status", value); break;
                case "LastUpdate": book.put("updated", value); break;
                case "Tags": book.put("tags", value); break;
                case "PressId": book.put("publisher", value); break;
                case "BookLength": book.put("length", value); break;
                case "TotalHitsCount": book.put("totalHits", value); break;
                case "DayHitsCount": book.put("dayHits", value); break;
                case "PushCount": book.put("recommendations", value); break;
                case "FavCount": book.put("favorites", value); break;
                case "IntroPreview": book.put("summary", value); break;
                case "Intro": case "Description": book.put("summary", value); break;
            }
        }
        book.put("id", String.valueOf(id));
        book.put("cover", "https://img.wenku8.com/image/" + (id / 1000) + "/" + id + "/" + id + "s.jpg");
        return book;
    }

    JSONObject list(String query, String sort, int page) throws Exception {
        return list(query,sort,page,"articlename");
    }
    JSONObject list(String query,String sort,int page,String type)throws Exception{
        if(!type.equals("articlename")&&!type.equals("author"))throw new IOException("不支持的搜索方式");
        String action = query.trim().isEmpty() ? "action=novellist&sort=" + sort + "&page=" + page + "&t=0"
            : "action=search&searchtype="+type+"&searchkey=" + encoded(query) + "&page=" + page + "&t=0";
        Document document = xml(request(action));
        NodeList items = document.getElementsByTagName("item");
        JSONArray books = new JSONArray();
        for (int index = 0; index < items.getLength(); index++) {
            JSONObject item = book((Element) items.item(index), 0);
            if (!item.optString("title").trim().isEmpty()) books.put(item);
        }
        JSONObject result = new JSONObject().put("books", books).put("page", page);
        NodeList pagination = document.getElementsByTagName("page");
        if (pagination.getLength() > 0) {
            Element node = (Element) pagination.item(0);
            result.put("pages", node.getAttribute("num"));
        } else if(!query.trim().isEmpty()) result.put("pages",1);
        return result;
    }

    JSONObject metadata(int id) throws Exception {
        return book(xml(request("action=book&do=meta&aid=" + id + "&t=0")).getDocumentElement(), id);
    }

    JSONObject introduction(int id) throws Exception {
        String text = request("action=book&do=intro&aid=" + id + "&t=0");
        if (text.trim().startsWith("<?xml") || text.trim().startsWith("<metadata")) {
            text = xml(text).getDocumentElement().getTextContent();
        }
        return new JSONObject().put("summary", text.trim());
    }

    JSONObject catalog(int id) throws Exception {
        Document document = xml(request("action=book&do=list&aid=" + id + "&t=0"));
        JSONArray chapters = new JSONArray();
        NodeList volumes = document.getElementsByTagName("volume");
        for (int index = 0; index < volumes.getLength(); index++) {
            Element volume = (Element) volumes.item(index);
            String title = volume.getFirstChild() == null ? "" : volume.getFirstChild().getTextContent().trim();
            NodeList entries = volume.getElementsByTagName("chapter");
            for (int chapterIndex = 0; chapterIndex < entries.getLength(); chapterIndex++) {
                Element entry = (Element) entries.item(chapterIndex);
                chapters.put(new JSONObject().put("id", entry.getAttribute("cid")).put("title", entry.getTextContent().trim())
                    .put("volume", title).put("edition", LibraryRules.edition(title)).put("volumeId", volume.getAttribute("vid")));
            }
        }
        return new JSONObject().put("chapters", chapters).put("volumes", volumes.getLength());
    }

    JSONObject chapter(int bookId, int chapterId) throws Exception {
        String text = request("action=book&do=text&aid=" + bookId + "&cid=" + chapterId + "&t=0");
        if (text.contains("因版权问题") || text.trim().equals("null")) throw new IOException("来源未提供本章阅读，请选择其他章节或本地文件");
        return new JSONObject().put("format", "text").put("text", text);
    }

    static boolean allowedImage(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443)
            && (host.endsWith(".wenku8.com") || host.endsWith(".wenku8.net") || host.equals("pic.777743.xyz"));
    }

    InputStream image(String address) throws Exception {
        URL url = new URL(address.replaceFirst("^http://", "https://"));
        boolean cover=url.getPath().matches("/image/\\d+/\\d+/\\d+s\\.(jpg|png)");
        if(cover){synchronized(covers){byte[] cached=covers.get(url.toString());if(cached!=null)return new ByteArrayInputStream(cached);}}
        InputStream stream=imageStream(url,cover?coverSlots:imageSlots);
        if(!cover)return stream;
        byte[] bytes;try(InputStream in=stream){bytes=readLimited(in,768*1024);}
        synchronized(covers){byte[] old=covers.put(url.toString(),bytes);coverBytes+=bytes.length-(old==null?0:old.length);while(coverBytes>6*1024*1024||covers.size()>96){String key=covers.keySet().iterator().next();coverBytes-=covers.remove(key).length;}}
        return new ByteArrayInputStream(bytes);
    }

    private InputStream imageStream(URL initial,Semaphore slots) throws Exception {
        URL url=initial;
        slots.acquire();
        HttpURLConnection active = null;
        try {
            for (int redirect = 0; redirect < 4; redirect++) {
                if (!allowedImage(url)) throw new IOException("不支持的图片地址");
                active = (HttpURLConnection) url.openConnection();
                configure(active);
                active.setRequestProperty("Referer", "https://www.wenku8.net/");
                int code = active.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = active.getHeaderField("Location");
                    if (location == null) throw new IOException("图片跳转地址为空");
                    url = new URL(url, location);
                    active.disconnect(); active = null;
                    continue;
                }
                checkStatus(active);
                final HttpURLConnection connection = active;
                return new FilterInputStream(decodedStream(active)) {
                    int count; boolean closed;
                    @Override public int read() throws IOException {
                        int value = super.read(); if (value >= 0 && ++count > 8 * 1024 * 1024) throw new IOException("图片过大"); return value;
                    }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int size = in.read(bytes, offset, length); if (size > 0 && (count += size) > 8 * 1024 * 1024) throw new IOException("图片过大"); return size;
                    }
                    @Override public void close() throws IOException {
                        if (closed) return; closed = true;
                        try { super.close(); } finally { connection.disconnect(); slots.release(); }
                    }
                };
            }
            throw new IOException("图片跳转次数过多");
        } catch (Exception error) {
            if (active != null) active.disconnect();
            slots.release();
            throw error;
        }
    }
    void clearMemory(){synchronized(covers){covers.clear();coverBytes=0;}}
}
