package com.xiahu.reader;
import android.content.*;import android.content.pm.PackageInfo;import android.os.Build;import android.net.Uri;import org.json.*;import java.io.*;import java.net.URLEncoder;import java.nio.charset.StandardCharsets;
/** Public project metadata only. No repository credentials are stored in the app. */
final class ProjectInfo {
 static final String OWNER="XiaHusuper",REPOSITORY="light-novel-library",BASE="https://github.com/"+OWNER+"/"+REPOSITORY;
 static JSONObject info(Context c)throws Exception{PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),0);return new JSONObject().put("name","轻小说文库").put("version",p.versionName).put("versionCode",Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode).put("repository",BASE).put("private",false).put("updateMode","in-app");}
 static Uri page(String kind)throws Exception{String suffix;if(kind.equals("latest"))suffix="/releases/latest";else if(kind.equals("changelog"))suffix="/releases";else if(kind.equals("repository"))suffix="";else throw new IOException("不支持的项目页面");return Uri.parse(BASE+suffix);}
 static JSONObject notices(Context c)throws Exception{try(InputStream in=c.getAssets().open("about-notices.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1){if(out.size()+n>256*1024)throw new IOException("许可声明过大");out.write(bytes,0,n);}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}}
}
