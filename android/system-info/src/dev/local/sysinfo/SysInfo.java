package dev.local.sysinfo;

import android.content.Context;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

/** Read-only report of what this system reports about itself. Nothing is sent to the vehicle. */
final class SysInfo {
 private static final String[] PROP_HINTS={"ro.product","ro.board","ro.hardware","ro.soc","ro.chip","ro.build.version","ro.build.display","ro.build.id","ro.build.date","ro.build.type","ro.build.fingerprint","ro.boot","ro.vendor","model","car","avm","qnx","chery","jaecoo","skyworth","desay","mcu","hab","dvr","version"};

 private SysInfo(){}

 static String report(Context context){
  StringBuilder o=new StringBuilder();
  section(o,"Android");
  kv(o,"MODEL",Build.MODEL);
  kv(o,"MANUFACTURER",Build.MANUFACTURER);
  kv(o,"BRAND",Build.BRAND);
  kv(o,"DEVICE",Build.DEVICE);
  kv(o,"PRODUCT",Build.PRODUCT);
  kv(o,"BOARD",Build.BOARD);
  kv(o,"HARDWARE",Build.HARDWARE);
  kv(o,"BOOTLOADER",Build.BOOTLOADER);
  kv(o,"FINGERPRINT",Build.FINGERPRINT);
  kv(o,"DISPLAY",Build.DISPLAY);
  kv(o,"Android",Build.VERSION.RELEASE+" (SDK "+Build.VERSION.SDK_INT+")");
  kv(o,"Security patch",Build.VERSION.SECURITY_PATCH);
  kv(o,"Incremental",Build.VERSION.INCREMENTAL);

  section(o,"Linux / ליבה");
  kv(o,"os.version",System.getProperty("os.version"));
  line(o,cmd("uname","-a"));
  line(o,readFile("/proc/version"));
  line(o,readFile("/proc/cmdline"));

  section(o,"חומרה (CPU / SoC)");
  for(String line:readLines("/proc/cpuinfo")){
   String lower=line.toLowerCase(Locale.US);
   if(lower.startsWith("hardware")||lower.startsWith("model")||lower.startsWith("processor")||lower.startsWith("revision"))o.append(line).append('\n');
  }
  kv(o,"device-tree model",readFile("/proc/device-tree/model"));
  kv(o,"device-tree compatible",readFile("/sys/firmware/devicetree/base/compatible"));
  kv(o,"soc machine",readFile("/sys/devices/soc0/machine"));
  kv(o,"soc family",readFile("/sys/devices/soc0/family"));
  kv(o,"soc id",readFile("/sys/devices/soc0/soc_id"));

  section(o,"מאפייני מערכת (getprop) שרלוונטיים");
  StringBuilder all=new StringBuilder();
  int shown=0;
  for(String line:cmdLines("getprop")){
   all.append(line).append('\n');
   String lower=line.toLowerCase(Locale.US);
   for(String hint:PROP_HINTS)if(lower.contains(hint)){
    o.append(line).append('\n');
    shown++;
    break;
   }
  }
  if(shown==0)o.append("(לא נמצאו)\n");

  section(o,"QNX / AVM");
  o.append("הערה: מערכת Android רגילה לא רואה את מעבד ה-QNX או את מודול ה-AVM ישירות.\n");
  o.append("מה שמוצג כאן הוא מה ש-Android מדווח עליהם דרך מאפיינים, אם בכלל.\n");
  int qnx=0;
  for(String line:all.toString().split("\n")){
   String lower=line.toLowerCase(Locale.US);
   if(lower.contains("qnx")||lower.contains("avm")){
    o.append(line).append('\n');
    qnx++;
   }
  }
  if(qnx==0)o.append("(לא נמצא מאפיין עם QNX או AVM)\n");

  saveFull(context,all.toString(),o.toString());
  return o.toString();
 }

 private static void saveFull(Context context,String props,String report){
  File base=context.getExternalFilesDir(null);
  if(base==null)base=context.getFilesDir();
  File dir=new File(base,"logs");
  dir.mkdirs();
  try(FileOutputStream out=new FileOutputStream(new File(dir,"system-info.txt"))){
   out.write(("== דוח\n"+report+"\n== getprop מלא\n"+props).getBytes("UTF-8"));
  }catch(IOException ignored){}
 }

 private static void section(StringBuilder o,String title){o.append("\n== ").append(title).append('\n');}

 private static void kv(StringBuilder o,String key,String value){
  o.append(key).append(": ").append(value==null||value.isEmpty()?"(לא זמין)":value).append('\n');
 }

 private static void line(StringBuilder o,String value){
  if(value!=null&&!value.isEmpty())o.append(value).append('\n');
 }

 private static String readFile(String path){
  StringBuilder sb=new StringBuilder();
  for(String line:readLines(path))sb.append(line.replace("\u0000"," ")).append(' ');
  String text=sb.toString().trim();
  return text.isEmpty()?null:text;
 }

 private static java.util.List<String> readLines(String path){
  java.util.List<String> lines=new java.util.ArrayList<>();
  try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(path),"UTF-8"))){
   String line;
   while((line=in.readLine())!=null)lines.add(line);
  }catch(IOException ignored){}
  return lines;
 }

 private static String cmd(String... args){
  java.util.List<String> lines=cmdLines(args);
  return lines.isEmpty()?null:lines.get(0);
 }

 private static java.util.List<String> cmdLines(String... args){
  java.util.List<String> lines=new java.util.ArrayList<>();
  try{
   java.lang.Process p=Runtime.getRuntime().exec(args);
   try(BufferedReader in=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))){
    String line;
    while((line=in.readLine())!=null)lines.add(line);
   }
   p.waitFor();
  }catch(Exception ignored){}
  return lines;
 }
}
