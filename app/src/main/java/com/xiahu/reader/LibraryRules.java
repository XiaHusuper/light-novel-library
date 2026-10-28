package com.xiahu.reader;

import org.json.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Port of Novel Recommendation's import_library, collection_order and text_sections rules. */
final class LibraryRules {
    static final String NUM="(?:[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千廿卅]+)";
    static final Pattern SIDE=Pattern.compile("短篇|外[传傳]|番外|[后後]日[谈談]|蛇足|六面世界|universe|司波达也暗杀计划|特典|附[录錄]|广播剧|BD\\d",Pattern.CASE_INSENSITIVE);
    static final Pattern WEB=Pattern.compile("web|[网網][络絡]版",Pattern.CASE_INSENSITIVE);
    static final Pattern VOL=Pattern.compile("第?\\s*("+NUM+")\\s*[卷巻](?:\\s*[（(]?([上中下])(?:[）)]|(?=\\s|$|[-—])))?");
    static final String NAMED="(?:序章|序言|前言|楔子|后记|後記|终章|終章|尾声|尾聲|终夜|插图|插圖|番外|独白(?:\\s*[前后後])?|序|终|終)";
    static final Pattern START=Pattern.compile("^(?:第\\s*"+NUM+"\\s*[章话話节節回夜].*|"+NUM+"\\s*[章话話节節回夜](?=$|\\s|[:：、.．【「『（(]).*|"+NAMED+"(?=$|\\s|[:：\\[【「『（(]).*|CASE\\s*\\d{1,3}[^。！？!?]{0,50})$",Pattern.CASE_INSENSITIVE);
    static final Pattern MARKER=Pattern.compile("(?:第\\s*)?"+NUM+"\\s*[章话話节節回夜]|"+NAMED+"(?=$|\\s|[:：\\[【「『（(])",Pattern.CASE_INSENSITIVE);
    static String norm(String s){return Normalizer.normalize(s,Normalizer.Form.NFKC).trim();}
    static double number(String s){
        try{return Double.parseDouble(s);}catch(Exception ignored){}
        if(s.equals("廿"))return 20;if(s.equals("卅"))return 30;
        int total=0,current=0;
        for(char c:s.toCharArray()){
            int n="零一二三四五六七八九".indexOf(c);
            if(c=='〇')n=0;if(c=='两')n=2;
            if(n>=0)current=n;else{int unit=c=='十'?10:c=='百'?100:c=='千'?1000:0;if(unit==0)return -1;total+=(current==0?1:current)*unit;current=0;}
        }return total+current;
    }
    static final class Volume {String title;double number;int half;Volume(double n,String h){number=n;half="下".equals(h)?2:"中".equals(h)?1:0;title="第"+(n==(long)n?String.valueOf((long)n):String.valueOf(n))+"卷"+(h==null?"":"（"+h+"）");}}
    static Volume volume(String label,boolean explicit){
        String text=norm(label);if(SIDE.matcher(text).find()||text.contains("插图")||text.contains("插圖"))return null;
        Matcher m=VOL.matcher(text);if(m.find())return new Volume(number(m.group(1)),m.group(2));
        if(!explicit){m=Pattern.compile("(?<!\\d)(\\d+(?:\\.\\d+)?)(?!\\d)\\s*([上中下])?").matcher(text);while(m.find()){if(m.group(1).equals("0")&&text.substring(0,m.start()).matches("(?is).*RE$"))continue;return new Volume(number(m.group(1)),m.group(2));}}
        return null;
    }
    static String edition(String title){return WEB.matcher(title).find()?"web":SIDE.matcher(title).find()?"side":"main";}
    static String workTitle(String title,String filename){
        String s=norm(title);if(s.isEmpty()||s.contains("填写"))s=norm(filename.replaceFirst("(?i)\\.(epub|txt)$",""));
        s=s.replaceFirst("\\s*第\\s*"+NUM+"\\s*[卷巻].*$","").replaceFirst("(?i)\\s+(?:web|网络版|網絡版|外传|外傳|短篇|番外|特典|EX\\s*\\d).*$","");
        s=s.replaceFirst("\\s+\\d+(?:\\.\\d+)?(?:\\s*[上中下])?$","").trim();
        return s.isEmpty()?title:s;
    }
    static class Node {String title,target;List<Node> children=new ArrayList<>();Node(String t,String h){title=t;target=h;}}
    static class Section {String edition,title;double order=9999;int half;List<Node> nodes=new ArrayList<>();Section(String e,String t,List<Node> n,Volume v){edition=e;title=t;nodes.addAll(n);if(v!=null){order=v.number;half=v.half;}}}
    static boolean containsTarget(List<Node> nodes,String target){for(Node n:nodes)if(Objects.equals(n.target,target)||containsTarget(n.children,target))return true;return false;}
    static List<Node> body(Node n){if(n.children.isEmpty())return Collections.singletonList(n);ArrayList<Node> a=new ArrayList<>();if(n.target!=null&&!containsTarget(n.children,n.target))a.add(new Node("卷首",n.target));a.addAll(n.children);return a;}
    static void add(List<Section> out,String e,String title,List<Node> nodes,Volume v){
        if(e.equals("main")&&v!=null)for(Section s:out)if(s.edition.equals(e)&&s.order==v.number&&s.half==v.half){s.nodes.addAll(nodes);return;}
        out.add(new Section(e,title,nodes,v));
    }
    static List<Section> sections(List<Node> tree,String filename,String bookTitle){
        ArrayList<Section> out=new ArrayList<>();boolean mixed=false;Set<Double> distinct=new HashSet<>();
        for(Node n:tree){if(n.title.matches("文[库庫](版)?")&&!n.children.isEmpty())mixed=true;Volume v=volume(n.title,true);if(v!=null)distinct.add(v.number+v.half*.00001);}
        if(mixed){
            ArrayList<Node> front=new ArrayList<>();
            for(Node n:tree){
                if(n.title.matches("文[库庫](版)?")){for(Node child:n.children){Volume v=volume(child.title,true);add(out,v==null?"side":"main",v==null?child.title:v.title,body(child),v);}}
                else if(WEB.matcher(n.title).find()){if(n.title.matches("(?i)WEB(版)?")&&!n.children.isEmpty()){for(Node child:n.children){Volume v=volume(child.title,true);add(out,"web",v==null?child.title:v.title,body(child),v);}}else add(out,"web",n.title,body(n),null);}
                else if(!n.children.isEmpty()){for(Node child:n.children)add(out,"side",child.title,body(child),null);}else front.add(n);
            }if(!front.isEmpty())add(out,"side","合集封面与说明",front,null);
        }else if(distinct.size()>=2){
            Section current=null;ArrayList<Node> front=new ArrayList<>();
            for(Node n:tree){Volume v=volume(n.title,true);
                if(WEB.matcher(n.title).find()){add(out,"web",n.title,body(n),null);current=null;}
                else if(v!=null){add(out,"main",v.title,body(n),v);current=n.children.isEmpty()?out.get(out.size()-1):null;}
                else if(current!=null)current.nodes.add(n);else if(!n.children.isEmpty())add(out,"side",n.title,body(n),null);else front.add(n);
            }if(!front.isEmpty())add(out,"side","合集附录",front,null);
        }else{
            boolean web=WEB.matcher(filename).find(),webRoots=false;for(Node n:tree)if(WEB.matcher(n.title).find())webRoots=true;
            if(webRoots&&!web){ArrayList<Node> front=new ArrayList<>();for(Node n:tree){if(WEB.matcher(n.title).find())add(out,"web",n.title,body(n),null);else front.add(n);}if(!front.isEmpty())out.add(0,new Section("web","封面与说明",front,null));}
            else{
                Volume v=volume(filename,false);if(v==null)v=volume(bookTitle,false);if(v==null&&distinct.size()==1)for(Node n:tree){v=volume(n.title,true);if(v!=null)break;}
                String e=web?"web":SIDE.matcher(filename).find()?"side":v!=null?"main":"side";
                String label=v!=null&&!e.equals("side")?v.title:filename.replaceFirst("(?i)\\.(epub|txt)$","");
                add(out,e,label,tree.size()==1&&!tree.get(0).children.isEmpty()?body(tree.get(0)):tree,v);
            }
        }
        // Numeric main volumes; explicitly numbered side-story series retain independent-story slots.
        out.sort((a,b)->{int e=Integer.compare(editionOrder(a.edition),editionOrder(b.edition));if(e!=0)return e;if(a.edition.equals("side"))return 0;int n=Double.compare(a.order,b.order);return n!=0?n:Integer.compare(a.half,b.half);});
        for(int i=0;i<out.size();i++){Section first=out.get(i);String key=collectionKey(first.title);if(key==null)continue;List<Section> same=new ArrayList<>();for(int j=i;j<out.size();j++)if(first.edition.equals(out.get(j).edition)&&key.equals(collectionKey(out.get(j).title)))same.add(out.get(j));if(same.size()<2)continue;same.sort((a,b)->Double.compare(collectionOrder(a.title),collectionOrder(b.title)));out.removeAll(same);out.addAll(i,same);i+=same.size()-1;}
        return out;
    }
    static int editionOrder(String e){return e.equals("main")?0:e.equals("side")?1:2;}
    static Matcher collection(String title){Matcher m=Pattern.compile("^(.+?)\\s*第\\s*("+NUM+")\\s*[卷巻](?:\\s*[（(]?([上中下]))?|^(.*?EX)\\s*(\\d+(?:\\.\\d+)?)(?!\\d)",Pattern.CASE_INSENSITIVE).matcher(norm(title));return m.find()?m:null;}
    static String collectionKey(String title){Matcher m=collection(title);return m==null?null:(m.group(1)!=null?m.group(1):m.group(4)).replaceAll("[^\\p{L}\\p{N}]","").toLowerCase(Locale.ROOT);}
    static double collectionOrder(String title){Matcher m=collection(title);if(m==null)return 0;return number(m.group(2)!=null?m.group(2):m.group(5))+("下".equals(m.group(3))?.0002:"中".equals(m.group(3))?.0001:0);}
    static JSONArray flatten(List<Section> sections)throws Exception{JSONArray result=new JSONArray();for(Section s:sections)flattenNodes(s.nodes,s,result,new HashSet<>());return result;}
    static void flattenNodes(List<Node> nodes,Section section,JSONArray out,Set<String> emitted)throws Exception{
        for(Node n:nodes){if(n.target!=null&&!containsTarget(n.children,n.target)&&emitted.add(n.target))out.put(new JSONObject().put("id",String.valueOf(out.length())).put("title",n.title).put("target",n.target).put("volume",section.title).put("edition",section.edition));flattenNodes(n.children,section,out,emitted);}
    }
    static JSONArray textChapters(String text,String filename)throws Exception{
        class Heading {int start;String title,group;boolean chapter;Heading(int s,String t,String g,boolean c){start=s;title=t;group=g;chapter=c;}}
        List<Heading> matches=new ArrayList<>();Matcher lines=Pattern.compile("(?m)^[^\\r\\n]+(?:\\r?\\n|$)").matcher(text);String current="正文";
        while(lines.find()&&matches.size()<5000){String label=lines.group().trim();if(label.length()>120)continue;Matcher prefix=Pattern.compile("^(第\\s*"+NUM+"\\s*[卷巻](?:\\s*[（(][上中下][）)])?)(?=\\s|$|[（(])").matcher(norm(label));
            if(prefix.find()){Matcher marker=MARKER.matcher(label.substring(Math.min(prefix.end(),label.length())));boolean chapter=marker.find();String group=chapter?label.substring(0,Math.min(label.length(),prefix.end()+marker.start())).trim():label;matches.add(new Heading(lines.start(),label,group,chapter));}
            else if(START.matcher(label).matches())matches.add(new Heading(lines.start(),label,null,true));
        }
        Matcher contents=Pattern.compile("(?im)^\\s*(?:目\\s*[录錄]|CONTENTS)\\s*$").matcher(text.substring(0,Math.min(20000,text.length())));
        if(contents.find()){List<Heading> dense=new ArrayList<>();Set<String> seen=new HashSet<>();int body=-1;for(Heading h:matches){if(h.start<contents.end()||h.start>=contents.end()+12000)continue;if(seen.contains(h.title)){body=h.start;break;}if(!dense.isEmpty()&&h.start-dense.get(dense.size()-1).start>180)break;dense.add(h);seen.add(h.title);}if(dense.size()>=3&&body>=0){final int b=body;for(Heading h:dense){boolean duplicate=false;for(Heading n:matches)if(n.start>=b&&n.title.equals(h.title))duplicate=true;if(duplicate)matches.remove(h);}}}
        JSONArray out=new JSONArray();String e=edition(filename);Volume fileVolume=volume(filename,false);if(fileVolume!=null)current=fileVolume.title;
        if(matches.isEmpty()){for(int start=0;start<text.length();){int end=Math.min(text.length(),start+14000);if(end<text.length()){int nl=text.indexOf('\n',end);if(nl>=0&&nl<end+1000)end=nl+1;}out.put(new JSONObject().put("title","第 "+(out.length()+1)+" 节").put("start",start).put("end",end).put("volume",current).put("edition",e));start=end;}}
        else{
            if(matches.get(0).start>0&&!text.substring(0,matches.get(0).start).trim().isEmpty())matches.add(0,new Heading(0,"卷首",matches.get(0).group,true));
            for(int i=0;i<matches.size();i++){Heading h=matches.get(i);if(h.group!=null)current=h.group;out.put(new JSONObject().put("title",h.title).put("start",h.start).put("end",i+1<matches.size()?matches.get(i+1).start:text.length()).put("volume",current).put("edition",e));}
        }for(int i=0;i<out.length();i++)out.getJSONObject(i).put("id",String.valueOf(i));return out;
    }
}
