package com.xiahu.reader;
import android.content.Intent;import android.content.ActivityNotFoundException;import android.app.Activity;import android.net.Uri;
/** Opens the official registration page in an external browser; no registration API. */
final class Registration {
 static final String URL="https://www.wenku8.net/register.php";
 static Intent webIntent(){return new Intent(Intent.ACTION_VIEW,Uri.parse(URL)).addCategory(Intent.CATEGORY_BROWSABLE);}
 static void open(Activity host){try{host.startActivity(browserIntent());}catch(ActivityNotFoundException missingBrowserCategory){host.startActivity(webIntent());}}
 static Intent browserIntent(){Intent intent=webIntent();intent.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));return intent;}
}
