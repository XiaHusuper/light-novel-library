package com.xiahu.reader;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import org.json.JSONObject;

/** Grants read access only to one verified update APK; never exposes the library or account files. */
public final class UpdateFileProvider extends ContentProvider {
    static Uri uri(Context c,String sha){return Uri.parse("content://"+c.getPackageName()+".updates/apk/"+sha+".apk");}
    @Override public boolean onCreate(){return true;}
    private File resolve(Uri uri)throws Exception {
        Context c=getContext();File folder=new File(c.getFilesDir(),"updates"),record=new File(folder,"verified.json"),apk=new File(folder,"verified.apk");
        AppUpdater.Release release=new AppUpdater.Release(new JSONObject(AppUpdater.readText(new FileInputStream(record),65536)));
        if(!uri.equals(uri(c,release.sha)))throw new FileNotFoundException("无效的更新文件");
        AppUpdater.verify(apk,release,c);return apk;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("更新文件仅允许读取");
        synchronized(AppUpdater.FILE_LOCK){try{return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception e){throw new FileNotFoundException("更新文件不可用");}}
    }
    @Override public String getType(Uri uri){synchronized(AppUpdater.FILE_LOCK){try{resolve(uri);return AppUpdater.MIME;}catch(Exception e){throw new IllegalArgumentException("更新文件不可用");}}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        synchronized(AppUpdater.FILE_LOCK){try{File file=resolve(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++){if(columns[i].equals(OpenableColumns.DISPLAY_NAME))row[i]="LightNovelLibrary-update.apk";else if(columns[i].equals(OpenableColumns.SIZE))row[i]=file.length();else throw new IllegalArgumentException("不支持的字段");}MatrixCursor cursor=new MatrixCursor(columns);cursor.addRow(row);return cursor;}catch(Exception e){throw new IllegalArgumentException("更新文件不可用");}}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
