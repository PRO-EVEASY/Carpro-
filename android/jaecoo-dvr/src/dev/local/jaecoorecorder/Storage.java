package dev.local.jaecoorecorder;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.StructStatVfs;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Bounded loop recording. Only this application's indexed clips may be removed. */
final class Storage {
 private static final Object LOCK=new Object();
 private static final long MIB=1024L*1024L;
 private static final int MAX_INDEX=10000,MAX_INDEX_BYTES=12*1024*1024;
 private static Destination activeDestination;
 final Context context;
 private final DvrSettings settings;
 private final AtomicFile index;
 private volatile String lastError="";
 Storage(Context c){context=c.getApplicationContext();settings=new DvrSettings(context);index=new AtomicFile(new File(context.getFilesDir(),"dvr-clips.json"));}
 File internal(){File d=context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);if(d==null)d=new File(context.getFilesDir(),"recordings");if(!d.exists()&&!d.mkdirs())throw new IllegalStateException("לא ניתן ליצור תיקיית הקלטה");return d;}
 long freeBytes(){return internal().getUsableSpace();}
 String tree(){return context.getSharedPreferences("recorder",0).getString("tree","");}
 void setTree(Uri uri){synchronized(LOCK){if(uri==null||!"content".equals(uri.getScheme())||!DocumentsContract.isTreeUri(uri))throw new IllegalArgumentException("בחר תיקיית USB לכתיבה");if(!context.getSharedPreferences("recorder",0).edit().putString("tree",uri.toString()).commit())throw new IllegalStateException("לא ניתן לשמור את תיקיית ה-USB");}}
 String status(){return lastError;}
 private JSONArray load()throws IOException{
  if(index.getBaseFile().exists()||new File(index.getBaseFile().getPath()+".bak").exists()){
   try(FileInputStream input=index.openRead()){
    java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
    while((n=input.read(buffer))!=-1){if(output.size()+n>MAX_INDEX_BYTES)throw new IOException("תיקיית ההקלטות חרגה ממגבלת הגודל");output.write(buffer,0,n);}
    JSONObject root=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));JSONArray rows=root.getJSONArray("clips");if(rows.length()>MAX_INDEX)throw new IOException("תיקיית ההקלטות חרגה ממגבלת ההקלטות");long sequence=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null){long value=row.optLong("order",sequence+1);if(value<=sequence)value=sequence+1;row.put("order",value);sequence=value;}}return rows;
   }catch(JSONException e){throw new IOException("לא ניתן לקרוא את תיקיית ההקלטות; הקבצים מוגנים",e);}
  }
  JSONArray migrated;
  try{migrated=new JSONArray(context.getSharedPreferences("recorder",0).getString("records","[]"));}catch(JSONException e){throw new IOException("לא ניתן לקרוא את תיקיית ההקלטות הקודמת; הקבצים מוגנים",e);}
  for(int i=0;i<migrated.length();i++){JSONObject row=migrated.optJSONObject(i);if(row!=null)try{if(!row.has("locked"))row.put("locked",false);row.put("state","complete");if(!row.has("order"))row.put("order",i+1L);}catch(JSONException e){throw new IOException(e);}}
  save(migrated);return migrated;
 }
 private void save(JSONArray rows)throws IOException{
  if(rows.length()>MAX_INDEX)throw new IOException("תיקיית ההקלטות מלאה; בדוק את ההקלטות המוגנות");
  FileOutputStream stream=null;try{JSONObject root=new JSONObject();root.put("schema",1);root.put("clips",rows);byte[] bytes=root.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_INDEX_BYTES)throw new IOException("תיקיית ההקלטות גדולה מדי");stream=index.startWrite();stream.write(bytes);index.finishWrite(stream);stream=null;}catch(JSONException e){throw new IOException("לא ניתן להכין את תיקיית ההקלטות",e);}finally{if(stream!=null)index.failWrite(stream);}
 }
 private static JSONObject copy(JSONObject row)throws JSONException{return new JSONObject(row.toString());}
 private static boolean ownName(String name){return name!=null&&(name.startsWith("Jaecoo_")||name.startsWith("DVR_"))&&name.endsWith(".mp4")&&name.matches("[A-Za-z0-9_.-]+")&&name.length()<180;}
 private boolean owned(JSONObject row)throws IOException{
  if(row==null||!ownName(row.optString("name")))return false;
  Uri uri=Uri.parse(row.optString("uri"));
  if("file".equals(uri.getScheme())){File file=new File(uri.getPath());String canonical=file.getCanonicalPath(),parent=internal().getCanonicalPath()+File.separator;return canonical.startsWith(parent)&&file.getName().equals(row.optString("name"))&&file.getParentFile().getCanonicalFile().equals(internal().getCanonicalFile());}
  if(!"content".equals(uri.getScheme())||!DocumentsContract.isDocumentUri(context,uri))return false;
  try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){return cursor!=null&&cursor.moveToFirst()&&row.optString("name").equals(cursor.getString(0));}catch(Exception e){throw new IOException("אין גישה להקלטה ב-USB",e);}
 }
 private boolean sameTarget(JSONObject row,boolean usb){if(row.optBoolean("usb")!=usb)return false;return !usb||tree().equals(row.optString("tree"));}
 private long usage(JSONArray rows,boolean usb){long total=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&sameTarget(row,usb))total+=Math.max(0,row.optLong("bytes"));}return total;}
 long usage(boolean usb){synchronized(LOCK){try{return usage(load(),usb);}catch(IOException e){lastError=e.getMessage();return 0;}}}
 int countLocked(){synchronized(LOCK){try{JSONArray rows=load();int n=0;for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i)!=null&&rows.optJSONObject(i).optBoolean("locked"))n++;return n;}catch(IOException e){lastError=e.getMessage();return 0;}}}
 JSONArray records(){synchronized(LOCK){try{JSONArray rows=load();List<JSONObject> clips=new ArrayList<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&!"recording".equals(row.optString("state")))clips.add(copy(row));}Collections.sort(clips,(a,b)->Long.compare(b.optLong("order",b.optLong("date")),a.optLong("order",a.optLong("date"))));JSONArray result=new JSONArray();for(JSONObject row:clips)result.put(row);return result;}catch(Exception e){lastError=e.getMessage();Log.e("JaecooDvrStorage","Index read failed; no deletion allowed",e);return new JSONArray();}}}
 private long available(boolean usb)throws IOException{
  if(!usb)return freeBytes();String saved=tree();if(saved.isEmpty())throw new IOException("בחר תחילה תיקיית הקלטה ב-USB");Uri root=Uri.parse(saved);boolean write=false;for(UriPermission grant:context.getContentResolver().getPersistedUriPermissions())if(grant.isWritePermission()&&grant.getUri().equals(root)){write=true;break;}if(!write)throw new IOException("אין לתיקיית ה-USB הרשאת כתיבה קבועה");
  Uri directory=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root));
  try(Cursor cursor=context.getContentResolver().query(directory,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){if(cursor==null||!cursor.moveToFirst())throw new IOException("תיקיית USB אינה מחוברת");}catch(Exception e){throw new IOException("אין גישה לזיכרון ה-USB",e);}
  try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(directory,"r")){if(fd!=null){StructStatVfs stat=Os.fstatvfs(fd.getFileDescriptor());if(stat.f_bsize>0)return stat.f_bavail*stat.f_frsize;}}catch(Exception unsupported){Log.d("JaecooDvrStorage","Provider does not expose directory free-space",unsupported);}return Long.MAX_VALUE;
 }
 private long estimateNext(){return Math.max(16*MIB,Math.min(3500*MIB,((long)settings.bitrate()*settings.segmentSeconds()/8)+16*MIB));}
 private boolean deletePhysical(JSONObject row)throws IOException{
  if(!owned(row))throw new IOException("ההקלטה אינה שייכת לתיקיית האפליקציה; לא נמחקה");Uri uri=Uri.parse(row.optString("uri"));
  if("file".equals(uri.getScheme())){File file=new File(uri.getPath());return !file.exists()||file.delete();}
  try{return DocumentsContract.deleteDocument(context.getContentResolver(),uri);}catch(Exception e){throw new IOException("לא ניתן למחוק את ההקלטה ב-USB; בדוק את הזיכרון וההרשאות",e);}
 }
 private JSONArray without(JSONArray rows,int excluded){JSONArray result=new JSONArray();for(int i=0;i<rows.length();i++)if(i!=excluded)result.put(rows.opt(i));return result;}
 private void ensureSpace(boolean usb,long incoming)throws IOException{
  JSONArray rows=load();long quota=settings.quotaBytes(usb),reserve=settings.reserveBytes(),free=available(usb);
  if(incoming>quota)throw new IOException("משך הקטע/קצב הסיביות שנבחרו אינם נכנסים למכסת האחסון");
  while(usage(rows,usb)+incoming>quota||free<reserve+incoming){
   if(!settings.loopRecording())throw new IOException("המכסה או השטח הפנוי מלאים; הקלטה מעגלית כבויה");
   int oldest=-1;long date=Long.MAX_VALUE;
   for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null||!sameTarget(row,usb)||row.optBoolean("locked")||"recording".equals(row.optString("state")))continue;long order=row.optLong("order",row.optLong("date"));if(order<date){oldest=i;date=order;}}
   if(oldest<0)throw new IOException("אין מספיק שטח פנוי; הקלטות מוגנות או פעילות אינן נמחקות");
   JSONObject victim=rows.optJSONObject(oldest);if(!deletePhysical(victim))throw new IOException("לא ניתן למחוק את ההקלטה הישנה; קטע חדש לא הותחל");rows=without(rows,oldest);save(rows);free=available(usb);
  }
 }
 void cleanup(boolean usb)throws IOException{synchronized(LOCK){try{ensureSpace(usb,estimateNext());lastError="";}catch(IOException e){lastError=e.getMessage();throw e;}}}
 Destination create(boolean usb)throws Exception{synchronized(LOCK){
  if(activeDestination!=null&&!activeDestination.closed)throw new IOException("קובץ הקלטה אחר עדיין פתוח");
  ensureSpace(usb,estimateNext());String name="DVR_"+new SimpleDateFormat("yyyyMMdd_HHmmss_SSS",Locale.US).format(new Date())+"_"+UUID.randomUUID().toString().substring(0,6)+".mp4";Destination destination;
  if(usb){Uri root=Uri.parse(tree());Uri directory=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root));Uri document=DocumentsContract.createDocument(context.getContentResolver(),directory,"video/mp4",name);if(document==null)throw new IOException("לא ניתן ליצור קובץ ב-USB");ParcelFileDescriptor fd=null;try{fd=context.getContentResolver().openFileDescriptor(document,"rw");if(fd==null)throw new IOException("לא ניתן לפתוח קובץ ב-USB");destination=new Destination(name,document,null,fd,true,tree());}catch(Exception e){if(fd!=null)fd.close();try{DocumentsContract.deleteDocument(context.getContentResolver(),document);}catch(Exception ignored){}throw e;}}
  else{File file=new File(internal(),name);ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_CREATE|ParcelFileDescriptor.MODE_READ_WRITE|ParcelFileDescriptor.MODE_TRUNCATE);destination=new Destination(name,Uri.fromFile(file),file,fd,false,"");}
  try{JSONArray rows=load();long highest=0;for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i)!=null)highest=Math.max(highest,rows.optJSONObject(i).optLong("order",i+1L));if(highest==Long.MAX_VALUE)throw new IOException("הגיע למגבלת מספר הקטע של ההקלטה");destination.order=highest+1;JSONObject pending=destination.row(0,0,"recording");rows.put(pending);save(rows);}catch(Exception e){destination.remove();throw e;}
  activeDestination=destination;lastError="";return destination;
 }}
 void completed(Destination destination,long size,long duration){synchronized(LOCK){
  destination.complete=true;
  try{JSONArray rows=load();JSONObject row=destination.row(Math.max(size,Math.max(destination.fd.getStatSize(),destination.file==null?0:destination.file.length())),Math.max(0,duration),"complete");boolean replaced=false;for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i)!=null&&destination.uri.toString().equals(rows.optJSONObject(i).optString("uri"))){rows.put(i,row);replaced=true;break;}if(!replaced)rows.put(row);save(rows);lastError="";}
  catch(Exception e){lastError="הווידאו מוגן; לא ניתן לכתוב לתיקיית ההקלטות: "+e.getMessage();Log.e("JaecooDvrStorage",lastError,e);throw new IllegalStateException(lastError,e);}
 }}
 boolean setLocked(String uri,boolean locked){synchronized(LOCK){try{JSONArray rows=load();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&uri.equals(row.optString("uri"))){row.put("locked",locked);if(locked)row.put("eventDate",System.currentTimeMillis());save(rows);if(activeDestination!=null&&uri.equals(activeDestination.uri.toString()))activeDestination.locked=locked;return true;}}return false;}catch(Exception e){lastError="Kilit durumu kaydedilemedi: "+e.getMessage();return false;}}}
 boolean setCurrentLocked(boolean locked){synchronized(LOCK){if(activeDestination==null)return false;activeDestination.locked=locked;return setLocked(activeDestination.uri.toString(),locked);}}
 boolean deleteRecord(String uri)throws IOException{synchronized(LOCK){JSONArray rows=load();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null||!uri.equals(row.optString("uri")))continue;if(row.optBoolean("locked"))throw new IOException("הסר את הנעילה לפני מחיקת הקלטה מוגנת");if("recording".equals(row.optString("state")))throw new IOException("לא ניתן למחוק הקלטה מתמשכת");if(!deletePhysical(row))throw new IOException("לא ניתן למחוק את ההקלטה");save(without(rows,i));return true;}return false;}}
 boolean annotate(String uri,JSONObject metadata){if(metadata==null)return false;synchronized(LOCK){try{if(metadata.toString().length()>65536)throw new IOException("מטא-נתוני הקליפ גדולים מדי");JSONArray rows=load();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&uri.equals(row.optString("uri"))){JSONObject extra=row.optJSONObject("metadata");if(extra==null)extra=new JSONObject();Iterator<String> keys=metadata.keys();while(keys.hasNext()){String key=keys.next();extra.put(key,metadata.get(key));}row.put("metadata",extra);save(rows);return true;}}return false;}catch(Exception e){lastError="Klip bilgisi kaydedilemedi: "+e.getMessage();return false;}}}
 /** Call on a worker thread: recover only indexed files with a finalized video track. */
 int recoverPending(){synchronized(LOCK){int recovered=0;try{JSONArray rows=load();for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null||!"recording".equals(row.optString("state")))continue;if(activeDestination!=null&&row.optString("uri").equals(activeDestination.uri.toString()))continue;try{if(!owned(row))continue;Uri uri=Uri.parse(row.optString("uri"));MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(context,uri,null);long duration=0;for(int track=0;track<extractor.getTrackCount();track++){MediaFormat format=extractor.getTrackFormat(track);if(format.getString(MediaFormat.KEY_MIME).startsWith("video/")&&format.containsKey(MediaFormat.KEY_DURATION))duration=Math.max(duration,format.getLong(MediaFormat.KEY_DURATION));}if(duration<=0)continue;long size=row.optLong("bytes");try(ParcelFileDescriptor fd=context.getContentResolver().openFileDescriptor(uri,"r")){if(fd!=null)size=Math.max(size,fd.getStatSize());}row.put("state","complete");row.put("recovered",true);row.put("seconds",duration/1000000);row.put("bytes",Math.max(0,size));recovered++;}finally{extractor.release();}}catch(Exception invalid){Log.d("JaecooDvrStorage","Incomplete clip retained",invalid);}}if(recovered>0)save(rows);}catch(Exception e){lastError="שחזור הקלטה: "+e.getMessage();}return recovered;}}
 final class Destination implements Closeable {
  final String name;final Uri uri;final File file;final ParcelFileDescriptor fd;final boolean usb;final String sourceTree;final long createdAt=System.currentTimeMillis();long order;volatile boolean locked,closed,complete;
  Destination(String n,Uri u,File f,ParcelFileDescriptor descriptor,boolean external,String tree){name=n;uri=u;file=f;fd=descriptor;usb=external;sourceTree=tree;}
  JSONObject row(long size,long duration,String state)throws JSONException{JSONObject row=new JSONObject();row.put("name",name);row.put("uri",uri.toString());row.put("usb",usb);row.put("tree",sourceTree);row.put("bytes",size);row.put("seconds",duration);row.put("date",createdAt);row.put("order",order);row.put("locked",locked);row.put("state",state);return row;}
  public void close()throws IOException{synchronized(LOCK){if(closed)return;closed=true;try{fd.close();}finally{if(activeDestination==this)activeDestination=null;}}}
  void remove(){try{close();}catch(IOException e){lastError=e.getMessage();}if(complete)return;synchronized(LOCK){try{JSONObject row=row(0,0,"recording");if(deletePhysical(row)){JSONArray rows=load();for(int i=0;i<rows.length();i++)if(rows.optJSONObject(i)!=null&&uri.toString().equals(rows.optJSONObject(i).optString("uri"))){save(without(rows,i));break;}}}catch(Exception e){lastError="לא ניתן לנקות הקלטה שנכשלה: "+e.getMessage();Log.w("JaecooDvrStorage",lastError,e);}}}
 }
}
