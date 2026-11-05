package dev.local.jaecoorecorder;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Optional trace file for field diagnosis. Writes only while enabled in settings. */
final class DvrLog {
 private static final long MAX_BYTES=2*1024*1024;
 private static final String EMPTY="היומן ריק. הפעל 'שמור יומן מפורט' בהגדרות ונסה שוב.";
 private static final Object LOCK=new Object();
 private final File file;
 private final File previous;
 private volatile boolean enabled;

 DvrLog(Context context){
  File base=context.getExternalFilesDir(null);
  if(base==null)base=context.getFilesDir();
  File dir=new File(base,"logs");
  dir.mkdirs();
  file=new File(dir,"dvr-trace.txt");
  previous=new File(dir,"dvr-trace.previous.txt");
 }

 void setEnabled(boolean value){enabled=value;}
 boolean enabled(){return enabled;}
 File file(){return file;}

 void write(String tag,String line){
  if(!enabled)return;
  synchronized(LOCK){
   try{
    if(file.length()>MAX_BYTES){
     if(previous.exists())previous.delete();
     file.renameTo(previous);
    }
    try(FileOutputStream out=new FileOutputStream(file,true)){
     out.write((new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date())+" "+tag+" "+line+"\n").getBytes("UTF-8"));
    }
   }catch(IOException ignored){}
  }
 }

 String saveSnapshot(String text){
  File out=new File(file.getParentFile(),"logcat-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt");
  try(FileOutputStream stream=new FileOutputStream(out)){
   stream.write(text.getBytes("UTF-8"));
   return out.getAbsolutePath();
  }catch(IOException e){
   return null;
  }
 }

 void clear(){
  synchronized(LOCK){
   file.delete();
   previous.delete();
  }
 }

 String tail(int count){
  if(!file.exists())return EMPTY;
  ArrayDeque<String> lines=new ArrayDeque<>();
  synchronized(LOCK){
   try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"))){
    String line;
    while((line=in.readLine())!=null){
     if(lines.size()==count)lines.removeFirst();
     lines.addLast(line);
    }
   }catch(IOException e){return "היומן לא נקרא: "+e.getMessage();}
  }
  if(lines.isEmpty())return EMPTY;
  StringBuilder out=new StringBuilder();
  for(String s:lines)out.append(s).append('\n');
  return out.toString();
 }
}
