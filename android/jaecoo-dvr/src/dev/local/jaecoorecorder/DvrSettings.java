package dev.local.jaecoorecorder;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.Locale;

/** Validated local DVR preferences. No vehicle configuration is written. */
public final class DvrSettings {
 private static final Object LOCK=new Object();
 private static final long MIB=1024L*1024L,GIB=1024L*MIB;
 private final SharedPreferences preferences;
 public DvrSettings(Context context){preferences=context.getApplicationContext().getSharedPreferences("dvr_settings",Context.MODE_PRIVATE);synchronized(LOCK){if(!preferences.getBoolean("preview25_upgrade",false)){try{String raw=preferences.getString("json","{}");JSONObject saved=new JSONObject(raw);if(saved.length()>0){saved.put("previewFps",25);preferences.edit().putString("json",saved.toString()).putBoolean("preview25_upgrade",true).commit();}else preferences.edit().putBoolean("preview25_upgrade",true).commit();}catch(Exception ignored){}}}}
 private static JSONObject defaults(){
  JSONObject o=new JSONObject();try{
   o.put("backend","auto");o.put("cameraIds","");o.put("habInputs","5,9,8,4");o.put("camera2Lens",false);o.put("schema",1);o.put("diagnosticLog",false);o.put("profile","balanced");o.put("width",1920);o.put("height",1440);o.put("fps",25);o.put("bitrate",12000000);o.put("codec","video/avc");o.put("segmentSeconds",60);o.put("gpu",true);o.put("lens",true);o.put("fov",100);o.put("autoStart",false);o.put("recordUsb",false);o.put("burnOverlay",true);o.put("loopRecording",true);o.put("lockOnShock",false);o.put("previewFps",25);o.put("idleGraceSeconds",20);o.put("internalQuotaGb",2);o.put("usbQuotaGb",8);o.put("reserveMb",512);o.put("gpsOverlay",true);o.put("speedOverlay",true);
  }catch(JSONException impossible){throw new IllegalStateException(impossible);}return o;
 }
 public JSONObject snapshot(){synchronized(LOCK){JSONObject result=defaults();try{JSONObject saved=new JSONObject(preferences.getString("json","{}"));Iterator<String> keys=saved.keys();while(keys.hasNext()){String key=keys.next();if(result.has(key))result.put(key,saved.get(key));}validate(result);return result;}catch(Exception invalid){return defaults();}}}
 public void apply(JSONObject patch){if(patch==null)throw new IllegalArgumentException("אין אובייקט הגדרות");synchronized(LOCK){JSONObject next=snapshot();try{
   String requested=patch.optString("profile",next.optString("profile"));if(!requested.equals(next.optString("profile")))preset(next,requested);
   Iterator<String> keys=patch.keys();while(keys.hasNext()){String key=keys.next();if(next.has(key)&&!"schema".equals(key))next.put(key,patch.get(key));}
   validate(next);if(!preferences.edit().putString("json",next.toString()).commit())throw new IllegalStateException("לא ניתן לשמור הגדרות DVR");
  }catch(JSONException e){throw new IllegalArgumentException("הגדרת DVR לא תקינה",e);}}}
 public void set(String key,Object value){try{apply(new JSONObject().put(key,value));}catch(JSONException e){throw new IllegalArgumentException("הגדרה לא תקינה",e);}}
 public void setProfile(String profile){synchronized(LOCK){JSONObject next=snapshot();try{preset(next,profile);apply(next);}catch(JSONException e){throw new IllegalArgumentException(e);}}}
 private static void preset(JSONObject o,String raw)throws JSONException{
  String name=raw==null?"balanced":raw.toLowerCase(Locale.ROOT);
  if("economy".equals(name)){o.put("width",1280);o.put("height",960);o.put("fps",25);o.put("bitrate",6000000);}
  else if("balanced".equals(name)){o.put("width",1920);o.put("height",1440);o.put("fps",25);o.put("bitrate",12000000);}
  else if("quality".equals(name)){o.put("width",2560);o.put("height",1920);o.put("fps",25);o.put("bitrate",20000000);}
  else if(!"custom".equals(name))throw new IllegalArgumentException("פרופיל לא מוכר: "+name);
  o.put("profile",name);o.put("codec","video/avc");o.put("gpu",true);o.put("lens",true);
 }
 private static void validate(JSONObject o)throws JSONException{
  String backend=o.getString("backend");if(!"auto".equals(backend)&&!"hab".equals(backend)&&!"camera2".equals(backend))throw new IllegalArgumentException("מקור מצלמה לא תקין");String ids=o.getString("cameraIds");if(ids.length()>120||(!ids.trim().isEmpty()&&ids.split(",").length>4))throw new IllegalArgumentException("הזן עד ארבעה מזהי Camera2");String[] hab=o.getString("habInputs").split(",");if(hab.length<1||hab.length>4)throw new IllegalArgumentException("מספר כניסות HAB 1–4");for(String id:hab){int n=Integer.parseInt(id.trim());if(n<0||n>255)throw new IllegalArgumentException("טווח כניסת HAB 0–255");}o.getBoolean("camera2Lens");
  int width=o.getInt("width"),height=o.getInt("height");if(!((width==1280&&height==960)||(width==1600&&height==1200)||(width==1920&&height==1440)||(width==2560&&height==1920)))throw new IllegalArgumentException("רזולוציית DVR לא נתמכת");
  int fps=o.getInt("fps");if(fps<1||fps>25)throw new IllegalArgumentException("טווח FPS מקורי 1–25");
  int bitrate=o.getInt("bitrate");if(bitrate<1000000||bitrate>80000000)throw new IllegalArgumentException("טווח קצב סיביות 1–80 Mbit/s");
  String codec=o.getString("codec");if(!"video/avc".equals(codec)&&!"video/hevc".equals(codec))throw new IllegalArgumentException("המקודד חייב להיות AVC או HEVC");
  int segment=o.getInt("segmentSeconds");if(segment<5||segment>300)throw new IllegalArgumentException("משך קטע 5–300 שניות");
  double fov=o.getDouble("fov");if(!Double.isFinite(fov)||fov<60||fov>140)throw new IllegalArgumentException("שדה ראייה 60–140 מעלות");
  int preview=o.getInt("previewFps");if(preview<1||preview>25)throw new IllegalArgumentException("טווח FPS לתצוגה 1–25");
  int grace=o.getInt("idleGraceSeconds");if(grace<3||grace>120)throw new IllegalArgumentException("השהיית כיבוי 3–120 שניות");
  double internal=o.getDouble("internalQuotaGb"),usb=o.getDouble("usbQuotaGb");if(!Double.isFinite(internal)||internal<.5||internal>64||!Double.isFinite(usb)||usb<1||usb>1024)throw new IllegalArgumentException("מכסת אחסון לא תקינה");
  int reserve=o.getInt("reserveMb");if(reserve<256||reserve>4096)throw new IllegalArgumentException("שמירת שטח פנוי 256–4096 MB");
  for(String key:new String[]{"gpu","lens","autoStart","recordUsb","burnOverlay","loopRecording","lockOnShock","gpsOverlay","speedOverlay"})o.getBoolean(key);
 }
 public String backend(){return snapshot().optString("backend");}public String cameraIds(){return snapshot().optString("cameraIds");}public String habInputs(){return snapshot().optString("habInputs");}
 public boolean diagnosticLog(){return snapshot().optBoolean("diagnosticLog");}public boolean gpu(){return snapshot().optBoolean("gpu");}public boolean lens(){return snapshot().optBoolean("lens");}
 public boolean autoStart(){return snapshot().optBoolean("autoStart");}public boolean automationEnabled(){return autoStart();}
 public boolean recordUsb(){return snapshot().optBoolean("recordUsb");}public boolean burnOverlay(){return snapshot().optBoolean("burnOverlay");}
 public boolean loopRecording(){return snapshot().optBoolean("loopRecording");}public boolean lockOnShock(){return snapshot().optBoolean("lockOnShock");}
 public int width(){return snapshot().optInt("width");}public int height(){return snapshot().optInt("height");}public int fps(){return snapshot().optInt("fps");}public int bitrate(){return snapshot().optInt("bitrate");}
 public int segmentSeconds(){return snapshot().optInt("segmentSeconds");}public int previewFps(){return snapshot().optInt("previewFps");}public int idleGraceSeconds(){return snapshot().optInt("idleGraceSeconds");}
 public long quotaBytes(boolean usb){return (long)(snapshot().optDouble(usb?"usbQuotaGb":"internalQuotaGb")*GIB);}public long reserveBytes(){return snapshot().optLong("reserveMb")*MIB;}
 public float fov(){return (float)snapshot().optDouble("fov");}public String codec(){return snapshot().optString("codec");}public String profile(){return snapshot().optString("profile");}
}
