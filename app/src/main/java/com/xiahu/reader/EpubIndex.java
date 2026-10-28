package com.xiahu.reader;

import org.json.*;
import org.w3c.dom.*;
import java.util.*;
import java.net.URLDecoder;
import java.io.IOException;

/** Preserve nested NCX/EPUB3 navigation and partition the spine at fragment boundaries. */
final class EpubIndex {
    interface Reader {byte[] read(String path,int max)throws Exception;}
    static String target(String base,String href)throws Exception{
        String[] pieces=href.split("#",2);String path=pieces[0].isEmpty()?base.split("#",2)[0]:LocalBooks.resolve(base,pieces[0]);
        return path+(pieces.length>1?"#"+URLDecoder.decode(pieces[1].replace("+","%2B"),"UTF-8"):"");
    }
    static List<Element> children(Element e,String name){List<Element> out=new ArrayList<>();for(org.w3c.dom.Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&(name==null||name.equals(n.getLocalName())||name.equals(n.getNodeName())))out.add((Element)n);return out;}
    static List<LibraryRules.Node> ncx(Element parent,String base)throws Exception{
        List<LibraryRules.Node> nodes=new ArrayList<>();
        for(Element p:children(parent,"navPoint")){
            String label="章节",href=null;
            for(Element child:children(p,null)){String name=child.getLocalName()==null?child.getNodeName():child.getLocalName();if(name.equals("navLabel"))label=child.getTextContent().trim();if(name.equals("content"))href=target(base,child.getAttribute("src"));}
            LibraryRules.Node n=new LibraryRules.Node(label,href);n.children=ncx(p,base);nodes.add(n);
        }return nodes;
    }
    static List<LibraryRules.Node> htmlNav(Element parent,String base)throws Exception{
        List<LibraryRules.Node> nodes=new ArrayList<>();
        for(Element li:children(parent,"li")){
            LibraryRules.Node node=null;List<Element> lists=new ArrayList<>();
            for(Element child:children(li,null)){String name=child.getLocalName()==null?child.getNodeName():child.getLocalName();if(name.equals("a")||name.equals("span"))node=new LibraryRules.Node(child.getTextContent().trim(),child.hasAttribute("href")?target(base,child.getAttribute("href")):null);else if(name.equals("ol"))lists.add(child);}
            if(node==null)node=new LibraryRules.Node("章节",null);for(Element list:lists)node.children.addAll(htmlNav(list,base));nodes.add(node);
        }return nodes;
    }
    static JSONObject parse(String id,String filename,Reader reader)throws Exception{
        Document container=LocalBooks.localXml(LocalBooks.decode(reader.read("META-INF/container.xml",262144)));
        Element root=(Element)container.getElementsByTagNameNS("*","rootfile").item(0);if(root==null)throw new IOException("EPUB 缺少内容目录");
        String opfPath=root.getAttribute("full-path");Document opf=LocalBooks.localXml(LocalBooks.decode(reader.read(opfPath,2097152)));
        String title=LocalBooks.first(opf,"title",filename.replaceFirst("(?i)\\.epub$","")),author=LocalBooks.first(opf,"creator","本地书籍");
        Map<String,String> paths=new HashMap<>();String ncx="",nav="",cover="";NodeList items=opf.getElementsByTagNameNS("*","item");
        for(int i=0;i<items.getLength();i++){Element item=(Element)items.item(i);String path=LocalBooks.resolve(opfPath,item.getAttribute("href"));paths.put(item.getAttribute("id"),path);if(item.getAttribute("media-type").contains("ncx"))ncx=path;if(Arrays.asList(item.getAttribute("properties").split("\\s+")).contains("nav"))nav=path;if(item.getAttribute("properties").contains("cover-image"))cover=path;}
        NodeList metas=opf.getElementsByTagNameNS("*","meta");for(int i=0;i<metas.getLength();i++){Element m=(Element)metas.item(i);if(m.getAttribute("name").equals("cover"))cover=paths.getOrDefault(m.getAttribute("content"),cover);}
        JSONArray spine=new JSONArray();NodeList refs=opf.getElementsByTagNameNS("*","itemref");Set<String> seen=new HashSet<>();
        for(int i=0;i<refs.getLength()&&spine.length()<5000;i++){Element ref=(Element)refs.item(i);String path=paths.get(ref.getAttribute("idref"));if(path!=null&&!ref.getAttribute("linear").equals("no")&&seen.add(path))spine.put(path);}
        List<LibraryRules.Node> tree=new ArrayList<>();
        if(!nav.isEmpty())try{Document d=LocalBooks.localXml(LocalBooks.decode(reader.read(nav,3145728)));NodeList navs=d.getElementsByTagNameNS("*","nav");for(int i=0;i<navs.getLength();i++){Element e=(Element)navs.item(i);String type=e.getAttributeNS("http://www.idpf.org/2007/ops","type");if(type.isEmpty())type=e.getAttribute("epub:type");if(type.contains("toc")||navs.getLength()==1){NodeList ol=e.getElementsByTagNameNS("*","ol");if(ol.getLength()>0)tree=htmlNav((Element)ol.item(0),nav);break;}}}catch(Exception ignored){}
        if(tree.isEmpty()&&!ncx.isEmpty())try{Document d=LocalBooks.localXml(LocalBooks.decode(reader.read(ncx,3145728)));NodeList maps=d.getElementsByTagNameNS("*","navMap");if(maps.getLength()>0)tree=ncx((Element)maps.item(0),ncx);}catch(Exception ignored){}
        if(tree.isEmpty())for(int i=0;i<spine.length();i++)tree.add(new LibraryRules.Node(i==0?"开篇":"第 "+(i+1)+" 节",spine.getString(i)));
        JSONArray navigation=new JSONArray();collectTargets(tree,navigation,new HashSet<>());
        JSONArray chapters=LibraryRules.flatten(LibraryRules.sections(tree,filename,title));
        // A stale TOC must never hide a readable spine.
        JSONArray valid=new JSONArray();for(int i=0;i<chapters.length();i++){JSONObject c=chapters.getJSONObject(i);if(seen.contains(c.getString("target").split("#",2)[0]))valid.put(c);}
        if(valid.length()==0)for(int i=0;i<spine.length();i++)valid.put(new JSONObject().put("title",i==0?"开篇":"第 "+(i+1)+" 节").put("target",spine.getString(i)).put("volume","正文").put("edition",LibraryRules.edition(filename)));
        for(int i=0;i<valid.length();i++)valid.getJSONObject(i).put("id",String.valueOf(i));
        if(valid.length()==0)throw new IOException("EPUB 没有可读章节");
        List<String> tags=new ArrayList<>();NodeList subjects=opf.getElementsByTagNameNS("*","subject");for(int i=0;i<subjects.getLength();i++)tags.add(subjects.item(i).getTextContent().trim());
        return new JSONObject().put("id",id).put("title",title).put("author",author).put("kind","epub").put("chapters",valid).put("spine",spine).put("navigation",navigation)
            .put("workTitle",LibraryRules.workTitle(title,filename)).put("tags",String.join(" ",tags)).put("summary",LocalBooks.first(opf,"description",""))
            .put("cover",cover.isEmpty()?"":LocalBooks.resourceUrl(id,cover));
    }
    static JSONArray parts(JSONObject book,int chapterIndex,Reader reader)throws Exception{
        JSONArray spine=book.getJSONArray("spine"),chapters=book.getJSONArray("chapters");String start=chapters.getJSONObject(chapterIndex).getString("target");
        List<String> targets=new ArrayList<>();for(int i=0;i<chapters.length();i++){String t=chapters.getJSONObject(i).optString("target");if(!targets.contains(t))targets.add(t);}
        JSONArray sourceNavigation=book.optJSONArray("navigation");
        Map<String,Integer> positions=new HashMap<>();for(int i=0;i<spine.length();i++)positions.put(spine.getString(i),i);
        // Preserve document order for fragment siblings even when section display order differs.
        targets.sort((a,b)->{int cmp=Integer.compare(positions.getOrDefault(a.split("#",2)[0],99999),positions.getOrDefault(b.split("#",2)[0],99999));return cmp!=0?cmp:Integer.compare(targetIndex(sourceNavigation,chapters,a),targetIndex(sourceNavigation,chapters,b));});
        int at=targets.indexOf(start);String end=at>=0&&at+1<targets.size()?targets.get(at+1):"";
        int first=positions.getOrDefault(start.split("#",2)[0],-1),last=end.isEmpty()?spine.length():positions.get(end.split("#",2)[0]);if(first<0)throw new IOException("目录目标不在 EPUB 阅读顺序中");
        String from=start.contains("#")?start.substring(start.indexOf('#')+1):"",to=end.contains("#")?end.substring(end.indexOf('#')+1):"";
        if(at==0){first=0;from="";}
        JSONArray result=new JSONArray();int bytes=0;
        for(int i=first;i<spine.length()&&i<=last;i++){
            if(i==last&&to.isEmpty())break;String path=spine.getString(i),html;
            try{html=LocalBooks.decode(reader.read(path,4194304));}catch(java.io.FileNotFoundException ignored){continue;}
            bytes+=html.length();if(bytes>8*1024*1024)throw new IOException("本章内容超过 8 MB，请拆分本地合集");
            int sameDocument=0;for(String t:targets)if(t.split("#",2)[0].equals(path)&&t.contains("#"))sameDocument++;
            result.put(new JSONObject().put("entry",path).put("html",html).put("from",i==positions.get(start.split("#",2)[0])?from:"").put("to",i==last?to:"").put("strictFrom",sameDocument>1).put("strictTo",!to.isEmpty()));
        }return result;
    }
    static int firstTargetIndex(JSONArray chapters,String target){for(int i=0;i<chapters.length();i++)if(chapters.optJSONObject(i).optString("target").equals(target))return i;return 99999;}
    static int targetIndex(JSONArray navigation,JSONArray chapters,String target){if(navigation!=null)for(int i=0;i<navigation.length();i++)if(target.equals(navigation.optString(i)))return i;return firstTargetIndex(chapters,target);}
    static void collectTargets(List<LibraryRules.Node> nodes,JSONArray out,Set<String> seen){for(LibraryRules.Node n:nodes){if(n.target!=null&&seen.add(n.target))out.put(n.target);collectTargets(n.children,out,seen);}}
}
