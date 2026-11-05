package dev.local.jaecoorecorder;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import dalvik.system.PathClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Read-only standard Android Automotive properties; no VDS writes or controls. */
public final class VehicleTelemetry implements AutoCloseable {
 public static final int SPEED_PROPERTY=0x11600207, IGNITION_PROPERTY=0x11400409, GEAR_PROPERTY=0x11400400;
 public static final long MAX_SPEED_AGE_MS=5000, MAX_STATE_READ_AGE_MS=5000;
 private final Context context;
 private final Handler main=new Handler(Looper.getMainLooper());
 private final Object lock=new Object();
 private ScheduledExecutorService worker;
 private volatile boolean started,carConnected;
 private Object car,propertyManager,propertyCallback;
 private Method getProperty,unregisterCallback;
 private Class<?> carClass;
 private Double canSpeed;
 private long canSpeedAtNs,ignitionReadAtMs,gearReadAtMs;
 private Boolean ignition;
 private int ignitionEnum=-1,gearEnum=-1;
 private String gear;
 private Location gps;
 private long gpsReceiptMs;
 private LocationManager locations;
 private boolean gpsRegistered;
 private volatile String carError="עדיין לא מחובר",locationError="";
 private long lastConnectAttemptMs;

 public static final class Snapshot {
  public final Double speedKmh,latitude,longitude,altitudeMeters;
  public final String speedSource,ignitionSource,gear,error;
  public final Boolean ignition;
  public final long speedAgeMs,locationAgeMs,observedAtElapsedMs;
  public final Float locationAccuracyMeters;
  public final boolean carConnected;
  public final Integer ignitionRaw;
  Snapshot(Double speed,String source,long age,Boolean power,String powerSource,String g,Location location,long locationAge,long observed,boolean connected,Integer raw,String problem){
   speedKmh=speed;speedSource=source;speedAgeMs=age;ignition=power;ignitionSource=powerSource;gear=g;locationAgeMs=locationAge;observedAtElapsedMs=observed;carConnected=connected;ignitionRaw=raw;error=problem;
   latitude=location==null?null:location.getLatitude();longitude=location==null?null:location.getLongitude();altitudeMeters=location!=null&&location.hasAltitude()?location.getAltitude():null;locationAccuracyMeters=location!=null&&location.hasAccuracy()?location.getAccuracy():null;
  }
  public String speedText(){return speedKmh==null?"--":String.format(Locale.US,"%.0f",speedKmh);}
  public String ignitionText(){return ignition==null?"Bilinmiyor":ignition?"פתוח":"כבוי / אביזרים";}
  public JSONObject toJson(){JSONObject o=new JSONObject();try{o.put("speed_kmh",speedKmh==null?JSONObject.NULL:speedKmh);o.put("speed_source",speedSource);o.put("speed_age_ms",speedAgeMs);o.put("ignition",ignition==null?JSONObject.NULL:ignition);o.put("ignition_source",ignitionSource);o.put("ignition_raw",ignitionRaw==null?JSONObject.NULL:ignitionRaw);o.put("gear",gear==null?JSONObject.NULL:gear);o.put("latitude",latitude==null?JSONObject.NULL:latitude);o.put("longitude",longitude==null?JSONObject.NULL:longitude);o.put("location_age_ms",locationAgeMs);o.put("location_accuracy_m",locationAccuracyMeters==null?JSONObject.NULL:locationAccuracyMeters);o.put("car_connected",carConnected);o.put("observed_elapsed_ms",observedAtElapsedMs);o.put("error",error);}catch(Exception ignored){}return o;}
 }
 public VehicleTelemetry(Context supplied){context=supplied.getApplicationContext();}
 public synchronized void start(){
  if(started)return;started=true;worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"DVR-readonly-telemetry");t.setDaemon(true);return t;});
  main.post(()->{if(started){connectCar();startGps();}});
  worker.scheduleWithFixedDelay(this::poll,250,500,TimeUnit.MILLISECONDS);
 }
 private final ServiceConnection connection=new ServiceConnection(){
  @Override public void onServiceConnected(ComponentName name,IBinder binder){
   if(!started)return;
   try{propertyManager=carClass.getMethod("getCarManager",String.class).invoke(car,"property");if(propertyManager==null)throw new IllegalStateException("אין מנהל מאפייני רכב");getProperty=propertyManager.getClass().getMethod("getProperty",Class.class,int.class,int.class);carConnected=true;carError="";registerSpeedCallback();}
   catch(Throwable failure){carConnected=false;carError=message(failure);}
  }
  @Override public void onServiceDisconnected(ComponentName name){carConnected=false;propertyManager=null;carError="שירות נתוני הרכב נותק";}
  @Override public void onBindingDied(ComponentName name){onServiceDisconnected(name);}
  @Override public void onNullBinding(ComponentName name){carConnected=false;carError="שירות נתוני הרכב החזיר חיבור ריק";}
 };
 private void connectCar(){
  if(!started||carConnected)return;long now=SystemClock.elapsedRealtime();if(lastConnectAttemptMs!=0&&now-lastConnectAttemptMs<10000)return;lastConnectAttemptMs=now;
  try{
   if(car!=null&&carClass!=null){try{carClass.getMethod("disconnect").invoke(car);}catch(Throwable ignored){}car=null;}
   ClassLoader loader=context.getClassLoader();try{carClass=Class.forName("android.car.Car",true,loader);}catch(ClassNotFoundException unavailable){loader=new PathClassLoader("/system/framework/android.car.jar",loader);carClass=Class.forName("android.car.Car",true,loader);}
   car=carClass.getMethod("createCar",Context.class,ServiceConnection.class,Handler.class).invoke(null,context,connection,main);
   if(car==null)throw new IllegalStateException("Android Automotive API לא זמין");carClass.getMethod("connect").invoke(car);
  }catch(Throwable failure){carConnected=false;carError=message(failure);}
 }
 private void registerSpeedCallback(){
  try{ClassLoader loader=propertyManager.getClass().getClassLoader();Class<?> callbackClass=Class.forName("android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback",true,loader);
   propertyCallback=Proxy.newProxyInstance(loader,new Class<?>[]{callbackClass},(proxy,method,args)->{
    if(method.getDeclaringClass()==Object.class){if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);if(method.getName().equals("equals"))return args!=null&&args.length==1&&proxy==args[0];if(method.getName().equals("toString"))return "DVR readonly speed callback";}
    if(started&&method.getName().equals("onChangeEvent")&&args!=null&&args.length>0&&args[0]!=null){try{acceptProperty(SPEED_PROPERTY,args[0]);}catch(Throwable failure){carError=message(failure);}}return null;
   });
   propertyManager.getClass().getMethod("registerCallback",callbackClass,int.class,float.class).invoke(propertyManager,propertyCallback,SPEED_PROPERTY,2f);unregisterCallback=propertyManager.getClass().getMethod("unregisterCallback",callbackClass);
  }catch(Throwable failure){carError="Callback: "+message(failure);}
 }
 private void poll(){
  if(!started)return;
  if(!carConnected||propertyManager==null){main.post(this::connectCar);return;}
  read(SPEED_PROPERTY,Float.class);read(IGNITION_PROPERTY,Integer.class);read(GEAR_PROPERTY,Integer.class);
 }
 private void read(int property,Class<?> type){try{Object manager=propertyManager;Method method=getProperty;if(manager!=null&&method!=null){Object value=method.invoke(manager,type,property,0);if(value!=null)acceptProperty(property,value);}}catch(Throwable failure){carError="0x"+Integer.toHexString(property)+": "+message(failure);}}
 private void acceptProperty(int property,Object propertyValue)throws Exception{
  Class<?> valueClass=propertyValue.getClass();int actual=((Number)valueClass.getMethod("getPropertyId").invoke(propertyValue)).intValue();if(actual!=property)return;
  int status=((Number)valueClass.getMethod("getStatus").invoke(propertyValue)).intValue();if(status!=0)return;
  String recoveredPrefix="0x"+Integer.toHexString(property)+": ";if(carError.startsWith(recoveredPrefix)||(property==SPEED_PROPERTY&&carError.startsWith("Callback:")))carError="";
  Object value=valueClass.getMethod("getValue").invoke(propertyValue);long timestamp=((Number)valueClass.getMethod("getTimestamp").invoke(propertyValue)).longValue();long now=SystemClock.elapsedRealtime();
  synchronized(lock){
   if(property==SPEED_PROPERTY&&value instanceof Number){double kmh=Math.abs(((Number)value).doubleValue())*3.6;if(Double.isFinite(kmh)&&kmh>=0&&kmh<=400&&timestamp>0){canSpeed=kmh;canSpeedAtNs=timestamp;}}
   else if(property==IGNITION_PROPERTY&&value instanceof Number){int raw=((Number)value).intValue();ignitionEnum=raw;ignition=raw==4||raw==5?Boolean.TRUE:raw>=1&&raw<=3?Boolean.FALSE:null;ignitionReadAtMs=now;}
   else if(property==GEAR_PROPERTY&&value instanceof Number){gearEnum=((Number)value).intValue();gear=decodeGear(gearEnum);gearReadAtMs=now;}
  }
 }
 private static String decodeGear(int value){switch(value){case 1:return "N";case 2:return "R";case 4:return "P";case 8:return "D";case 16:return "1";case 32:return "2";case 64:return "3";case 128:return "4";case 256:return "5";case 512:return "6";case 1024:return "7";case 2048:return "8";case 4096:return "9";default:return null;}}
 private final LocationListener listener=new LocationListener(){
  @Override public void onLocationChanged(Location value){if(!started||value==null||value.isFromMockProvider()||!LocationManager.GPS_PROVIDER.equals(value.getProvider()))return;if(value.hasAccuracy()&&value.getAccuracy()>50)return;synchronized(lock){gps=new Location(value);gpsReceiptMs=SystemClock.elapsedRealtime();}}
  @Override public void onProviderDisabled(String provider){if(LocationManager.GPS_PROVIDER.equals(provider))locationError="GPS כבוי";}
  @Override public void onProviderEnabled(String provider){locationError="";}
  @Override public void onStatusChanged(String provider,int status,Bundle extras){}
 };
 private void startGps(){
  if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){locationError="Konum izni verilmedi";return;}
  try{locations=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);if(locations==null||!locations.getAllProviders().contains(LocationManager.GPS_PROVIDER)){locationError="אין ספק GPS";return;}locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,listener,Looper.getMainLooper());gpsRegistered=true;Location previous=locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);if(previous!=null)listener.onLocationChanged(previous);}catch(Throwable failure){locationError=message(failure);}
 }
 public void setGpsActive(boolean active){main.post(()->{if(active){if(started&&!gpsRegistered)startGps();}else{try{if(locations!=null&&gpsRegistered)locations.removeUpdates(listener);}catch(Throwable ignored){}gpsRegistered=false;}});}
 public Snapshot snapshot(){long now=SystemClock.elapsedRealtime(),nowNs=SystemClock.elapsedRealtimeNanos();synchronized(lock){long canAge=canSpeedAtNs>0?(nowNs-canSpeedAtNs)/1000000:Long.MAX_VALUE;if(canAge<0&&canAge>-500)canAge=0;boolean canFresh=carConnected&&canSpeed!=null&&canAge>=0&&canAge<=MAX_SPEED_AGE_MS;
   long gpsAge=gps==null?Long.MAX_VALUE:(nowNs-gps.getElapsedRealtimeNanos())/1000000;boolean gpsFresh=gps!=null&&gpsAge>=0&&gpsAge<=MAX_SPEED_AGE_MS&&now-gpsReceiptMs<=MAX_SPEED_AGE_MS;
   Double speed=canFresh?canSpeed:null;String source=canFresh?"CAN / Android Car":"Yok";long age=canFresh?canAge:-1;
   if(speed==null&&gpsFresh&&gps.hasSpeed()&&(!gps.hasSpeedAccuracy()||gps.getSpeedAccuracyMetersPerSecond()<=5)){double kmh=Math.abs(gps.getSpeed())*3.6;if(Double.isFinite(kmh)&&kmh<=400){speed=kmh;source="GPS";age=gpsAge;}}
   boolean stateFresh=carConnected&&ignitionReadAtMs>0&&now-ignitionReadAtMs<=MAX_STATE_READ_AGE_MS;Boolean power=stateFresh?ignition:null;String powerSource=stateFresh&&ignitionEnum>=0?"Android Car IGNITION_STATE="+ignitionEnum:"Yok";String selectedGear=carConnected&&gearReadAtMs>0&&now-gearReadAtMs<=MAX_STATE_READ_AGE_MS?gear:null;
   return new Snapshot(speed,source,age,power,powerSource,selectedGear,gpsFresh?new Location(gps):null,gpsFresh?gpsAge:-1,now,carConnected,stateFresh?ignitionEnum:null,carError+(locationError.isEmpty()?"":"; "+locationError));
  }}
 public JSONObject evidence(){Snapshot state=snapshot();JSONObject result=state.toJson();try{result.put("mode","read-only");result.put("speed_property","0x11600207");result.put("speed_units","provider m/s; display km/h");result.put("ignition_property","0x11400409");result.put("gear_property","0x11400400");result.put("max_speed_age_ms",MAX_SPEED_AGE_MS);result.put("can_speed_raw_age_ms",canSpeedAtNs>0?(SystemClock.elapsedRealtimeNanos()-canSpeedAtNs)/1000000:-1);result.put("gear_raw",gearEnum);}catch(Exception ignored){}return result;}
 private static String message(Throwable error){Throwable cause=error;while(cause instanceof InvocationTargetException&&((InvocationTargetException)cause).getTargetException()!=null)cause=((InvocationTargetException)cause).getTargetException();String text=cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage());return text.length()>240?text.substring(0,240):text;}
 @Override public synchronized void close(){if(!started)return;started=false;if(worker!=null){worker.shutdownNow();worker=null;}main.post(()->{try{if(propertyManager!=null&&propertyCallback!=null&&unregisterCallback!=null)unregisterCallback.invoke(propertyManager,propertyCallback);}catch(Throwable ignored){}try{if(car!=null&&carClass!=null)carClass.getMethod("disconnect").invoke(car);}catch(Throwable ignored){}try{if(locations!=null&&gpsRegistered)locations.removeUpdates(listener);}catch(Throwable ignored){}carConnected=false;propertyManager=null;propertyCallback=null;car=null;gpsRegistered=false;});}
}
