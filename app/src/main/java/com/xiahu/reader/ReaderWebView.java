package com.xiahu.reader;
import android.content.*;import android.graphics.Rect;import android.view.*;import android.webkit.*;
/** Keep Android's selection handles; replace reading actions with exactly three options. */
final class ReaderWebView extends WebView {
 boolean reading;ReaderWebView(Context c){super(c);}
 @Override public ActionMode startActionMode(ActionMode.Callback cb,int type){return super.startActionMode(wrap(cb),type);}
 @Override public ActionMode startActionMode(ActionMode.Callback cb){return super.startActionMode(wrap(cb));}
 private ActionMode.Callback wrap(ActionMode.Callback original){return new ActionMode.Callback2(){boolean custom;
  void menu(Menu m){m.clear();m.add(0,7101,0,"复制").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);m.add(0,7102,1,"书签").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);m.add(0,7103,2,"插图").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);}
  public boolean onCreateActionMode(ActionMode mode,Menu m){boolean result=original.onCreateActionMode(mode,m);custom=reading&&(getHitTestResult()==null||getHitTestResult().getType()!=HitTestResult.EDIT_TEXT_TYPE);if(custom){menu(m);evaluateJavascript("window.App&&window.App.captureSelection()",null);return true;}return result;}
  public boolean onPrepareActionMode(ActionMode mode,Menu m){boolean result=original.onPrepareActionMode(mode,m);if(custom){menu(m);return true;}return result;}
  public boolean onActionItemClicked(ActionMode mode,MenuItem item){if(!custom)return original.onActionItemClicked(mode,item);String action=item.getItemId()==7101?"copy":item.getItemId()==7102?"bookmark":"illustrations";evaluateJavascript("window.App&&window.App.selectionAction('"+action+"')",v->mode.finish());return true;}
  public void onDestroyActionMode(ActionMode mode){original.onDestroyActionMode(mode);}
  public void onGetContentRect(ActionMode mode,View view,Rect rect){if(original instanceof ActionMode.Callback2)((ActionMode.Callback2)original).onGetContentRect(mode,view,rect);else super.onGetContentRect(mode,view,rect);}
 };}
}
