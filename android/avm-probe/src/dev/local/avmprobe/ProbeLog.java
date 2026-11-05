package dev.local.avmprobe;

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

/** Append-only event log in app storage, readable with a file manager or adb pull. */
final class ProbeLog {
 private static final long MAX_BYTES=2*1024*1024;
 private final File file;
 private final File previous;

 ProbeLog(Context context){
  File base=context.getExternalFilesDir(null);
  if(base==null)base=context.getFilesDir();
  File dir=new File(base,"logs");
  dir.mkdirs();
  file=new File(dir,"avm-probe.txt");
  previous=new File(dir,"avm-probe.previous.txt");
 }

 File file(){return file;}

 /** Saves a logcat snapshot next to the event log. Returns null when the write fails. */
 File saveSnapshot(String text){
  File out=new File(file.getParentFile(),"logcat-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt");
  try(FileOutputStream stream=new FileOutputStream(out)){
   stream.write(text.getBytes("UTF-8"));
   return out;
  }catch(IOException e){
   return null;
  }
 }

 synchronized void write(String text){
  try{
   if(file.length()>MAX_BYTES){
    if(previous.exists())previous.delete();
    file.renameTo(previous);
   }
   try(FileOutputStream out=new FileOutputStream(file,true)){
    out.write((new SimpleDateFormat("dd/MM HH:mm:ss",Locale.US).format(new Date())+"  "+text+"\n").getBytes("UTF-8"));
   }
  }catch(IOException ignored){}
 }

 synchronized String tail(int count){
  if(!file.exists())return "היומן ריק. הפעל מעקב או סריקה כדי ליצור רשומות.";
  ArrayDeque<String> lines=new ArrayDeque<>();
  try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),"UTF-8"))){
   String line;
   while((line=in.readLine())!=null){
    if(lines.size()==count)lines.removeFirst();
    lines.addLast(line);
   }
  }catch(IOException e){return "היומן לא נקרא: "+e.getMessage();}
  StringBuilder out=new StringBuilder();
  for(String s:lines)out.append(s).append('\n');
  return out.toString();
 }
}
