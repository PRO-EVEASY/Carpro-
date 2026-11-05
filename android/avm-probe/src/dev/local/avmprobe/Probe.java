package dev.local.avmprobe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Size;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only inventory of camera sources visible to an ordinary app. Sends nothing to the vehicle. */
final class Probe {
 private static final int USB_CLASS_VIDEO=14;
 private static final String[] PACKAGE_HINTS={"desay","skyworth","avm","dvr","hab","cam","surround"};
 private static final String[] PROP_HINTS={"cam","avm","hab","dvr","desay","surround","vehicle"};

 private Probe(){}

 static String report(Context context){
  StringBuilder out=new StringBuilder();
  List<String> ids=new ArrayList<>();
  boolean hab=false;
  boolean uvc=false;
  out.append("== מצלמות Camera2\n");
  int backOrExternal=0;
  CameraManager cm=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
  try{
   String[] list=cm.getCameraIdList();
   if(list.length==0)out.append("(אין מצלמות)\n");
   for(String id:list){
    CameraCharacteristics c=cm.getCameraCharacteristics(id);
    Integer facing=c.get(CameraCharacteristics.LENS_FACING);
    Integer level=c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    Size[] yuv=map==null?new Size[0]:map.getOutputSizes(ImageFormat.YUV_420_888);
    int[] caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
    boolean logical=false;
    if(caps!=null)for(int cap:caps)if(cap==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)logical=true;
    Set<String> physical=c.getPhysicalCameraIds();
    out.append("id=").append(id)
     .append("  ").append(facingName(facing))
     .append("  level=").append(level==null?"?":String.valueOf(level))
     .append("  YUV=").append(yuv.length>0?yuv[0].getWidth()+"x"+yuv[0].getHeight():"-");
    if(logical)out.append("  logical");
    if(!physical.isEmpty())out.append("  physical=").append(physical);
    out.append('\n');
    ids.add(id);
    if(facing!=null&&(facing==CameraCharacteristics.LENS_FACING_BACK||facing==CameraCharacteristics.LENS_FACING_EXTERNAL))backOrExternal++;
   }
  }catch(CameraAccessException|RuntimeException e){
   out.append("שגיאת Camera2: ").append(e.getMessage()).append('\n');
  }

  out.append("\n== התקני וידאו בתיקיית /dev\n");
  File[] nodes=new File("/dev").listFiles();
  int found=0;
  if(nodes!=null)for(File f:nodes){
   String name=f.getName().toLowerCase(Locale.US);
   if(name.startsWith("hab")){hab=true;}
   if(name.startsWith("hab")||name.startsWith("video")||name.startsWith("v4l")||name.contains("desay")||name.contains("avm")){
    out.append(f.getAbsolutePath()).append('\n');
    found++;
   }
  }
  if(found==0)out.append("(לא נראה או אין הרשאה לקרוא /dev)\n");

  out.append("\n== USB\n");
  UsbManager um=(UsbManager)context.getSystemService(Context.USB_SERVICE);
  int usbCount=0;
  for(UsbDevice d:um.getDeviceList().values()){
   usbCount++;
   out.append(describeUsb(d)).append('\n');
   for(int i=0;i<d.getInterfaceCount();i++){
    UsbInterface itf=d.getInterface(i);
    if(itf.getInterfaceClass()==USB_CLASS_VIDEO)uvc=true;
   }
  }
  if(usbCount==0)out.append("(אין התקני USB מחוברים)\n");

  out.append("\n== חבילות רלוונטיות\n");
  PackageManager pm=context.getPackageManager();
  int pkgs=0;
  for(PackageInfo p:pm.getInstalledPackages(0)){
   String name=p.packageName.toLowerCase(Locale.US);
   for(String hint:PACKAGE_HINTS)if(name.contains(hint)){
    out.append(p.packageName).append("  ").append(p.versionName).append('\n');
    pkgs++;
    if(name.contains("desay")||name.contains("skyworth"))hab=true;
    break;
   }
  }
  if(pkgs==0)out.append("(לא נמצאו)\n");

  out.append("\n== מאפיינים (getprop)\n");
  int props=0;
  try{
   Process proc=Runtime.getRuntime().exec(new String[]{"getprop"});
   try(BufferedReader in=new BufferedReader(new InputStreamReader(proc.getInputStream(),"UTF-8"))){
    String line;
    while((line=in.readLine())!=null){
     String lower=line.toLowerCase(Locale.US);
     for(String hint:PROP_HINTS)if(lower.contains(hint)){
      out.append(line).append('\n');
      props++;
      if(lower.contains("hab")||lower.contains("desay"))hab=true;
      break;
     }
    }
   }
  }catch(Exception e){
   out.append("getprop לא זמין: ").append(e.getMessage()).append('\n');
  }
  if(props==0)out.append("(לא נמצאו מאפיינים מתאימים)\n");

  out.append("\n== מסקנה (ממצאים, לא ודאות)\n");
  if(hab)out.append("• נמצאו סימני DesaySV/HAB. בהגדרות ה-DVR בחר 'DesaySV HAB' והשתמש בקלטים שברשימה למעלה.\n");
  if(backOrExternal>=1)out.append("• Camera2: נמצאו ").append(backOrExternal).append(" מצלמות אחוריות/חיצוניות (מזהים: ").append(ids).append("). אפשר לנסות 'Android Camera2' עם המזהים האלה.\n");
  if(uvc)out.append("• נמצא התקן USB וידאו (UVC). אפשר לנסות אותו כמקור.\n");
  if(!hab&&backOrExternal==0&&!uvc)out.append("• לא נמצא מקור וידאו שאפליקציה רגילה יכולה לגשת אליו. ייתכן שהמצלמות זמינות רק לשירותי המערכת.\n");
  out.append("הדוח מבוסס על מה שנחשף לאפליקציה רגילה. הוא לא מוכיח מה הרכב דורש בפועל.\n");
  return out.toString();
 }

 static String describeUsb(UsbDevice d){
  if(d==null)return "(לא ידוע)";
  String product=d.getProductName();
  return String.format(Locale.US,"%04x:%04x  class=%d  %s",d.getVendorId(),d.getProductId(),d.getDeviceClass(),product==null?"":product);
 }

 private static String facingName(Integer facing){
  if(facing==null)return "לא ידוע";
  if(facing==CameraCharacteristics.LENS_FACING_FRONT)return "קדמית";
  if(facing==CameraCharacteristics.LENS_FACING_BACK)return "אחורית";
  if(facing==CameraCharacteristics.LENS_FACING_EXTERNAL)return "חיצונית";
  return "לא ידוע";
 }
}
