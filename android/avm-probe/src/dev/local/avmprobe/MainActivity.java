package dev.local.avmprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Locale;

public final class MainActivity extends Activity {
 private static final int BG=0xff07080b,WHITE=0xfff6f1e7,MUTED=0xff9ca3ae,GOLD=0xffd6b274,CARD=0xff12141a;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private ProbeLog log;
 private CameraManager cameras;
 private TextView output;
 private Button watchButton;
 private boolean watching;
 private static final int CAPTURE_MS=60000;
 private static final String[] LOG_KEYS={"camera","cam","avm","hab","desay","skyworth","dvr","usb","uvc","video","surround"};
 private final Runnable periodicCapture=new Runnable(){public void run(){captureLogcat();handler.postDelayed(this,CAPTURE_MS);}};

 private final CameraManager.AvailabilityCallback availability=new CameraManager.AvailabilityCallback(){
  public void onCameraAvailable(String id){event("מצלמה זמינה: "+id);}
  public void onCameraUnavailable(String id){event("מצלמה לא זמינה: "+id);}
 };
 private final BroadcastReceiver usb=new BroadcastReceiver(){
  public void onReceive(Context c,Intent i){
   UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
   boolean attached=UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction());
   event((attached?"USB חובר: ":"USB נותק: ")+Probe.describeUsb(d));
  }
 };

 @Override protected void onCreate(Bundle state){
  super.onCreate(state);
  log=new ProbeLog(this);
  cameras=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
  buildUi();
  if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},7);
  event("האפליקציה נפתחה");
 }

 @Override protected void onDestroy(){
  if(watching)stopWatch();
  super.onDestroy();
 }

 private void buildUi(){
  LinearLayout root=new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL);
  root.setPadding(dp(18),dp(14),dp(18),dp(14));
  root.setBackgroundColor(BG);
  setContentView(root);

  TextView title=text("בדיקת DVR ל-AVM",26,GOLD);
  title.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
  root.addView(title);
  root.addView(text("קריאה בלבד: סריקה של מצלמות, USB וחבילות. לא נשלחים פקודות לרכב.",12,MUTED));
  space(root,12);

  LinearLayout row=new LinearLayout(this);
  Button scan=button("▶  סרוק עכשיו",v->scan());
  scan.setTextColor(BG);
  scan.setBackgroundColor(GOLD);
  row.addView(scan,new LinearLayout.LayoutParams(0,dp(52),1));
  watchButton=button("◉  התחל מעקב",v->toggleWatch());
  LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,dp(52),1);
  wp.leftMargin=dp(8);
  row.addView(watchButton,wp);
  Button logButton=button("▤  יומן",v->showLog());
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);
  lp.leftMargin=dp(8);
  row.addView(logButton,lp);
  root.addView(row);
  space(root,8);
  Button capture=button("▼  לכוד לוגים עכשיו",v->captureLogcat());
  capture.setTextColor(BG);
  capture.setBackgroundColor(GOLD);
  root.addView(capture,new LinearLayout.LayoutParams(-1,dp(52)));
  space(root,12);

  output=new TextView(this);
  output.setTypeface(Typeface.MONOSPACE);
  output.setTextSize(12);
  output.setTextColor(WHITE);
  output.setTextIsSelectable(true);
  output.setPadding(dp(14),dp(12),dp(14),dp(12));
  output.setBackgroundColor(CARD);
  output.setText("לחץ 'סרוק עכשיו' כדי לבדוק אילו מקורות וידאו נחשפים למכשיר.");
  ScrollView scroll=new ScrollView(this);
  scroll.addView(output);
  root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
 }

 private void scan(){
  String report=Probe.report(this);
  output.setText(report);
  log.write("סריקה:\n"+report);
 }

 private void toggleWatch(){
  if(watching)stopWatch();
  else startWatch();
 }

 private void startWatch(){
  cameras.registerAvailabilityCallback(availability,handler);
  IntentFilter filter=new IntentFilter();
  filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
  filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
  registerReceiver(usb,filter);
  watching=true;
  watchButton.setText("■  עצור מעקב");
  event("מעקב התחיל");
  handler.postDelayed(periodicCapture,CAPTURE_MS);
 }

 private void stopWatch(){
  cameras.unregisterAvailabilityCallback(availability);
  unregisterReceiver(usb);
  handler.removeCallbacks(periodicCapture);
  watching=false;
  watchButton.setText("◉  התחל מעקב");
  event("מעקב נעצר");
 }

 private void captureLogcat(){
  final boolean readLogs=checkSelfPermission("android.permission.READ_LOGS")==PackageManager.PERMISSION_GRANTED;
  new Thread(()->{
   String text=dumpLogcat();
   File saved=log.saveSnapshot(text);
   int lines=0;
   for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n')lines++;
   String summary="לכידת לוגים: "+lines+" שורות · READ_LOGS "+(readLogs?"מאושר":"לא מאושר")+" · "+(saved==null?"שמירה נכשלה":saved.getName());
   handler.post(()->event(summary));
  }).start();
 }

 private String dumpLogcat(){
  StringBuilder out=new StringBuilder();
  try{
   Process p=Runtime.getRuntime().exec(new String[]{"logcat","-d","-t","3000","-v","threadtime"});
   try(BufferedReader in=new BufferedReader(new InputStreamReader(p.getInputStream(),"UTF-8"))){
    String line;
    while((line=in.readLine())!=null){
     String lower=line.toLowerCase(Locale.US);
     for(String key:LOG_KEYS)if(lower.contains(key)){
      out.append(line).append('\n');
      break;
     }
    }
   }
   p.waitFor();
  }catch(Exception e){
   out.append("logcat לא זמין: ").append(e.getMessage()).append('\n');
  }
  return out.toString();
 }

 private void event(String text){
  log.write(text);
  output.append("\n"+text);
 }

 private void showLog(){
  TextView view=text(log.tail(200),11,WHITE);
  view.setTypeface(Typeface.MONOSPACE);
  view.setTextIsSelectable(true);
  ScrollView scroll=new ScrollView(this);
  scroll.setPadding(dp(14),dp(8),dp(14),dp(8));
  scroll.addView(view);
  new AlertDialog.Builder(this).setTitle("יומן").setMessage("הקובץ: "+log.file().getAbsolutePath()).setView(scroll).setPositiveButton("סגור",null).show();
 }

 private TextView text(String value,float size,int color){
  TextView v=new TextView(this);
  v.setText(value);
  v.setTextSize(size);
  v.setTextColor(color);
  return v;
 }

 private Button button(String title,View.OnClickListener click){
  Button b=new Button(this);
  b.setText(title);
  b.setAllCaps(false);
  b.setTextSize(14);
  b.setTextColor(WHITE);
  b.setBackgroundColor(CARD);
  b.setOnClickListener(click);
  return b;
 }

 private void space(LinearLayout parent,int height){
  parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));
 }

 private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
}
