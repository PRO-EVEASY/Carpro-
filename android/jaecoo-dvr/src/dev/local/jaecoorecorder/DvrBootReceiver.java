package dev.local.jaecoorecorder;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/** Android lifecycle signals only. Screen-off is not represented as ignition-off. */
public final class DvrBootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent broadcast){
  if(broadcast==null||broadcast.getAction()==null)return;String incoming=broadcast.getAction();String command;
  if(Intent.ACTION_BOOT_COMPLETED.equals(incoming)||Intent.ACTION_MY_PACKAGE_REPLACED.equals(incoming)||Intent.ACTION_USER_UNLOCKED.equals(incoming))command="AUTO";
  else if(Intent.ACTION_SCREEN_ON.equals(incoming)||Intent.ACTION_USER_PRESENT.equals(incoming))command="SCREEN_ON";
  else if(Intent.ACTION_SCREEN_OFF.equals(incoming))command="SCREEN_OFF";
  else if(Intent.ACTION_SHUTDOWN.equals(incoming))command="POWER_OFF";
  else return;
  boolean enabled=new DvrSettings(context).autoStart();
  if(!enabled){boolean running=false;try{ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);if(manager!=null)for(ActivityManager.RunningServiceInfo service:manager.getRunningServices(32))if(service.service.getPackageName().equals(context.getPackageName())&&service.service.getClassName().equals(CameraService.class.getName())){running=true;break;}}catch(RuntimeException ignored){}if(!running)return;}
  try{Intent service=new Intent(context,CameraService.class).setAction(command).putExtra("power_signal",incoming);if(Build.VERSION.SDK_INT>=26)context.startForegroundService(service);else context.startService(service);}catch(RuntimeException failure){Log.w("JaecooDvrBoot","DVR lifecycle signal not delivered: "+command,failure);}
 }
}
