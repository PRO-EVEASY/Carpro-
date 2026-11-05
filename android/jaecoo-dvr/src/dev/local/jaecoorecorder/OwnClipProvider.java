package dev.local.jaecoorecorder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** Read-only, explicitly granted sharing of clips already present in our own index. */
public final class OwnClipProvider extends ContentProvider {
 public static final String AUTHORITY="dev.local.jaecoorecorder.clips";
 public static Uri forClip(String uri){String token=Base64.encodeToString(uri.getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("clip").appendPath(token).build();}
 @Override public boolean onCreate(){return true;}
 private JSONObject record(Uri uri)throws FileNotFoundException{try{if(!AUTHORITY.equals(uri.getAuthority())||uri.getPathSegments().size()!=2||!"clip".equals(uri.getPathSegments().get(0)))throw new IllegalArgumentException("כתובת URI של הקלטה לא תקינה");String source=new String(Base64.decode(uri.getLastPathSegment(),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8);JSONArray rows=new Storage(getContext()).records();for(int k=0;k<rows.length();k++){JSONObject r=rows.optJSONObject(k);if(r!=null&&source.equals(r.optString("uri")))return r;}}catch(Exception e){throw new FileNotFoundException("הגישה להקלטה נדחתה: "+e.getMessage());}throw new FileNotFoundException("ההקלטה לא נמצאה");}
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{try{return openIndexedRead(uri,mode);}catch(FileNotFoundException e){android.util.Log.e("JaecooDvrPlayback","Clip provider: "+e.getMessage());throw e;}}
 private ParcelFileDescriptor openIndexedRead(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("הקלטה לקריאה בלבד");JSONObject row=record(uri);Uri source=Uri.parse(row.optString("uri"));if("content".equals(source.getScheme())&&!AUTHORITY.equals(source.getAuthority())){ParcelFileDescriptor descriptor=getContext().getContentResolver().openFileDescriptor(source,"r");if(descriptor==null)throw new FileNotFoundException("לא ניתן לפתוח קובץ הקלטה ב-USB");return descriptor;}if("file".equals(source.getScheme()))try{File f=new File(source.getPath()).getCanonicalFile();if(!inside(f,getContext().getFilesDir())&&!inside(f,getContext().getExternalFilesDir(null)))throw new FileNotFoundException("קובץ מחוץ לאפליקציה נדחה");if(!f.isFile()||!f.canRead())throw new FileNotFoundException("קובץ ההקלטה לא קיים או שאינו נקרא");return ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY);}catch(java.io.IOException e){throw new FileNotFoundException(e.getMessage());}throw new FileNotFoundException("מיקום הקלטה לא נתמך");}
 @Override public android.content.res.AssetFileDescriptor openAssetFile(Uri uri,String mode)throws FileNotFoundException{ParcelFileDescriptor descriptor=openFile(uri,mode);long length=descriptor.getStatSize();return new android.content.res.AssetFileDescriptor(descriptor,0,length<0?android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH:length);}
 private boolean inside(File file,File directory)throws java.io.IOException{return directory!=null&&file.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator);}
 @Override public String getType(Uri uri){return "video/mp4";}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(columns);try{JSONObject row=record(uri);String name=row.optString("name");if(name.isEmpty())name="Jaecoo-DVR-"+row.optLong("date")+".mp4";Object[] values=new Object[columns.length];for(int k=0;k<columns.length;k++){if(OpenableColumns.DISPLAY_NAME.equals(columns[k]))values[k]=name;else if(OpenableColumns.SIZE.equals(columns[k]))values[k]=row.optLong("bytes");}c.addRow(values);}catch(Exception ignored){}return c;}
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
 @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
 @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
}
