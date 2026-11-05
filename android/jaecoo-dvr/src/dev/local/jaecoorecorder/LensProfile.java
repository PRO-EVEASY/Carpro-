package dev.local.jaecoorecorder;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.ByteBuffer;
import java.util.Locale;
import org.json.JSONArray;

/** Manual equidistant approximation, not the vehicle manufacturer's calibration. */
public final class LensProfile {
 public static final int CAMERAS=4, PARAMETERS=8;
 private final SharedPreferences preferences;
 private final float[][] values=new float[CAMERAS][PARAMETERS];
 public LensProfile(Context context){
  preferences=context.getSharedPreferences("manual_lens_profiles",Context.MODE_PRIVATE);
  defaults();
  try{JSONArray root=new JSONArray(preferences.getString("profiles","[]"));
   if(root.length()==CAMERAS)for(int camera=0;camera<CAMERAS;camera++){
    JSONArray row=root.getJSONArray(camera);float[] proposed=new float[PARAMETERS];
    for(int parameter=0;parameter<PARAMETERS;parameter++)proposed[parameter]=(float)row.getDouble(parameter);
    validate(proposed);values[camera]=proposed;
   }
  }catch(Exception ignored){defaults();}
 }
 private void defaults(){for(int i=0;i<CAMERAS;i++)values[i]=new float[]{.5f,.5f,(float)(1/Math.PI),(float)(4/(3*Math.PI)),100f,0f,0f,1f};}
 public synchronized float[] get(int camera){checkCamera(camera);return values[camera].clone();}
 public synchronized float[][] all(){float[][] result=new float[CAMERAS][];for(int i=0;i<CAMERAS;i++)result[i]=values[i].clone();return result;}
 public synchronized float[] flatten(){float[] result=new float[CAMERAS*PARAMETERS];for(int i=0;i<CAMERAS;i++)System.arraycopy(values[i],0,result,i*PARAMETERS,PARAMETERS);return result;}
 public synchronized void set(int camera,float[] profile){checkCamera(camera);validate(profile);values[camera]=profile.clone();save();}
 public synchronized void reset(){defaults();save();}
 private void save(){try{JSONArray root=new JSONArray();for(float[] profile:values){JSONArray row=new JSONArray();for(float value:profile)row.put((double)value);root.put(row);}preferences.edit().putString("profiles",root.toString()).apply();}catch(Exception e){throw new IllegalStateException("לא ניתן לשמור הגדרות עדשה",e);}}
 private static void checkCamera(int camera){if(camera<0||camera>=CAMERAS)throw new IllegalArgumentException("מצלמה לא תקינה");}
 private static void validate(float[] p){
  if(p==null||p.length!=PARAMETERS)throw new IllegalArgumentException("פרופיל עדשה חייב לכלול 8 ערכים");
  for(float v:p)if(Float.isNaN(v)||Float.isInfinite(v))throw new IllegalArgumentException("ערך עדשה לא תקין");
  if(p[0]<0||p[0]>1||p[1]<0||p[1]>1||p[2]<.05f||p[2]>2f||p[3]<.05f||p[3]>2f||p[4]<30||p[4]>150||Math.abs(p[5])>1||Math.abs(p[6])>1||p[7]<.5f||p[7]>3f)throw new IllegalArgumentException("ערך העדשה מחוץ לטווח הנתמך");
 }
 public synchronized String summary(){StringBuilder text=new StringBuilder("תיקון עדשה ידני (לא כיול יצרן)");for(int i=0;i<CAMERAS;i++)text.append(String.format(Locale.US,"\nמצלמה %d: FOV %.0f°, זום %.2f, מרכז %.3f/%.3f",i+1,values[i][4],values[i][7],values[i][0],values[i][1]));return text.toString();}
 public static native void clearCache();
 public static native boolean composeCorrected(byte[] a,byte[] b,byte[] c,byte[] d,ByteBuffer output,boolean semiPlanar,int inputWidth,int inputHeight,int outputWidth,int outputHeight,float[] profiles);
}
