package dev.local.jaecoorecorder;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Optional actual accelerometer event source; never estimates impact from CAN or GPS. */
public final class DvrShockSensor implements SensorEventListener,AutoCloseable {
 public static final float THRESHOLD_G=2.5f;
 public static final long COOLDOWN_MS=30000,MIN_PULSE_MS=30,WARMUP_MS=500;
 private final SensorManager manager;
 private final Sensor sensor;
 private final Runnable callback;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final float[] gravity=new float[3];
 private boolean enabled,recording,listening,closed,initialized;
 private long lastSampleNs,startMs,pulseStartMs=-1,lastEventMs=-1;
 private int pulseSamples;
 private float lastPeak;
 private String problem="";
 public DvrShockSensor(Context context,Runnable eventCallback){manager=(SensorManager)context.getApplicationContext().getSystemService(Context.SENSOR_SERVICE);sensor=manager==null?null:manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);callback=eventCallback;}
 public synchronized boolean isAvailable(){return sensor!=null;}
 public synchronized boolean isListening(){return listening;}
 public synchronized float lastPeakG(){return lastPeak;}
 public synchronized String status(){if(sensor==null)return "אין חיישן תאוצה";if(!problem.isEmpty())return problem;return listening?"זיהוי פגיעה פעיל (2.5g)":enabled?"ממתין להקלטה":"זיהוי פגיעה כבוי";}
 public synchronized void setEnabled(boolean value){if(closed)return;enabled=value;reconcile();}
 public synchronized void setRecording(boolean value){if(closed)return;recording=value;reconcile();}
 private void reconcile(){
  boolean wanted=enabled&&recording&&sensor!=null&&!closed;
  if(wanted&&!listening){initialized=false;pulseStartMs=-1;pulseSamples=0;lastSampleNs=0;lastPeak=0;startMs=SystemClock.elapsedRealtime();try{listening=manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME,main);problem=listening?"":"לא ניתן לפתוח רישום חיישן תאוצה";}catch(RuntimeException failure){listening=false;problem="הגישה לחיישן התאוצה נדחתה";}}
  else if(!wanted&&listening){manager.unregisterListener(this);listening=false;initialized=false;pulseStartMs=-1;pulseSamples=0;}
 }
 @Override public void onSensorChanged(SensorEvent event){
  boolean fire=false;
  synchronized(this){
   if(!listening||closed||!enabled||!recording||event.sensor.getType()!=Sensor.TYPE_ACCELEROMETER||event.values.length<3)return;
   float x=event.values[0],y=event.values[1],z=event.values[2];if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))return;
   long now=SystemClock.elapsedRealtime();
   if(!initialized){gravity[0]=x;gravity[1]=y;gravity[2]=z;initialized=true;lastSampleNs=event.timestamp;return;}
   double rawDt=(event.timestamp-lastSampleNs)/1000000000.0;if(rawDt<=0)return;if(rawDt>0.2){pulseStartMs=-1;pulseSamples=0;}double dt=Math.max(0.001,Math.min(0.2,rawDt));lastSampleNs=event.timestamp;long sampleMs=event.timestamp/1000000;
   float alpha=(float)Math.exp(-dt/0.8);float[] v=event.values;double linearSquared=0;
   for(int i=0;i<3;i++){gravity[i]=alpha*gravity[i]+(1-alpha)*v[i];double d=v[i]-gravity[i];linearSquared+=d*d;}
   float g=(float)(Math.sqrt(linearSquared)/SensorManager.GRAVITY_EARTH);lastPeak=Math.max(lastPeak,g);
   if(now-startMs<WARMUP_MS){pulseStartMs=-1;pulseSamples=0;return;}
   if(g>=THRESHOLD_G){if(pulseStartMs<0){pulseStartMs=sampleMs;pulseSamples=1;}else pulseSamples++;if(pulseSamples>=2&&sampleMs-pulseStartMs>=MIN_PULSE_MS&&(lastEventMs<0||now-lastEventMs>=COOLDOWN_MS)){lastEventMs=now;fire=true;pulseStartMs=-1;pulseSamples=0;}}
   else{pulseStartMs=-1;pulseSamples=0;}
  }
  if(fire&&callback!=null){try{callback.run();}catch(RuntimeException ignored){}}
 }
 @Override public void onAccuracyChanged(Sensor ignored,int accuracy){}
 @Override public synchronized void close(){if(closed)return;closed=true;enabled=false;recording=false;reconcile();}
}
