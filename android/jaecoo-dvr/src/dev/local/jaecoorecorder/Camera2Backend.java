package dev.local.jaecoorecorder;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
/** Portable framework cameras only. No OEM camera IDs, services or vehicle controls are guessed. */
public final class Camera2Backend implements CaptureBackend {
 private volatile int fpsLimit=25;
 @Override public int frameRateLimit(){return fpsLimit;}
 private final CameraManager manager;
 private final Object lock=new Object();
 private HandlerThread thread;
 private Handler handler;
 private CameraDevice device;
 private CameraCaptureSession session;
 private ImageReader reader;
 private Config config;
 private volatile boolean stopped=true;
 private boolean available;
 private String failure="",description="Camera2 כבוי";
 private long frames,convertNs;
 private int generation;
 public Camera2Backend(Context context){manager=(CameraManager)context.getApplicationContext().getSystemService(Context.CAMERA_SERVICE);}
 public static JSONArray probe(Context context){JSONArray result=new JSONArray();CameraManager m=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);if(m==null)return result;try{Set<Set<String>> concurrent=Build.VERSION.SDK_INT>=30?m.getConcurrentCameraIds():Collections.emptySet();for(String id:m.getCameraIdList()){JSONObject c=new JSONObject();c.put("cameraId",id);try{CameraCharacteristics ch=m.getCameraCharacteristics(id);c.put("lensFacing",ch.get(CameraCharacteristics.LENS_FACING));c.put("sensorOrientation",ch.get(CameraCharacteristics.SENSOR_ORIENTATION));JSONArray ranges=new JSONArray();Range<Integer>[] rr=ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);if(rr!=null)for(Range<Integer> r:rr)ranges.put(new JSONArray().put(r.getLower()).put(r.getUpper()));c.put("availableFpsRanges",ranges);JSONArray sizes=new JSONArray();StreamConfigurationMap map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);Size[] yy=map==null?null:map.getOutputSizes(ImageFormat.YUV_420_888);if(yy!=null)for(Size z:yy)sizes.put(new JSONObject().put("width",z.getWidth()).put("height",z.getHeight()));c.put("yuvSizes",sizes);JSONArray groups=new JSONArray();for(Set<String> group:concurrent)if(group.contains(id)){JSONArray g=new JSONArray();for(String member:group)g.put(member);groups.put(g);}c.put("concurrentGroups",groups);}catch(Exception e){c.put("error",e.toString());}result.put(c);}}catch(Exception e){try{result.put(new JSONObject().put("error",e.toString()));}catch(Exception ignored){}}return result;}
 @Override public void open(Config requested)throws IOException{
  if(manager==null)throw new IOException("Camera2 framework unavailable");if(requested.cameraId==null||requested.cameraId.isEmpty())throw new IOException("Select a framework cameraId");
  final int ticket;
  synchronized(lock){if(thread!=null)throw new IOException("Camera2 already open");ticket=++generation;config=requested;stopped=false;available=false;failure="";frames=0;convertNs=0;thread=new HandlerThread("DVR-Camera2-"+requested.slot);thread.start();handler=new Handler(thread.getLooper());}
  CountDownLatch ready=new CountDownLatch(1);
  try{CameraCharacteristics ch=manager.getCameraCharacteristics(requested.cameraId);StreamConfigurationMap map=ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);Size source=chooseSize(map==null?null:map.getOutputSizes(ImageFormat.YUV_420_888),requested.width,requested.height);Range<Integer> fps=chooseFps(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES),requested.targetFps);fpsLimit=fps==null?requested.targetFps:Math.min(requested.targetFps,fps.getUpper());
   synchronized(lock){reader=ImageReader.newInstance(source.getWidth(),source.getHeight(),ImageFormat.YUV_420_888,3);reader.setOnImageAvailableListener(value->{synchronized(lock){if(!stopped&&ticket==generation&&reader==value){available=true;lock.notifyAll();}}},handler);description="Camera2 id="+requested.cameraId+" source="+source+" -> "+requested.width+"x"+requested.height+" UYVY center-crop; requested="+requested.targetFps+"fps; AE="+(fps==null?"device default":fps.toString());}
   manager.openCamera(requested.cameraId,new CameraDevice.StateCallback(){
    @Override public void onOpened(CameraDevice opened){synchronized(lock){if(stopped||ticket!=generation){opened.close();ready.countDown();return;}device=opened;}try{opened.createCaptureSession(Collections.singletonList(reader.getSurface()),new CameraCaptureSession.StateCallback(){
     @Override public void onConfigured(CameraCaptureSession configured){synchronized(lock){if(stopped||ticket!=generation){configured.close();ready.countDown();return;}session=configured;}try{CaptureRequest.Builder b=opened.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);b.addTarget(reader.getSurface());b.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);if(fps!=null)b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps);configured.setRepeatingRequest(b.build(),null,handler);}catch(Exception e){fail(e.toString(),ticket);}finally{ready.countDown();}}
     @Override public void onConfigureFailed(CameraCaptureSession bad){fail("Camera2 stream configuration rejected",ticket);ready.countDown();}
    },handler);}catch(Exception e){fail(e.toString(),ticket);ready.countDown();}}
    @Override public void onDisconnected(CameraDevice value){value.close();fail("Camera2 disconnected",ticket);ready.countDown();}
    @Override public void onError(CameraDevice value,int error){value.close();fail("Camera2 error="+error,ticket);ready.countDown();}
   },handler);
   if(!ready.await(5,TimeUnit.SECONDS))throw new IOException("Camera2 open timeout");synchronized(lock){if(stopped)throw new IOException("Camera2 open cancelled");if(!failure.isEmpty())throw new IOException(failure);if(session==null)throw new IOException("Camera2 session unavailable");}
  }catch(InterruptedException e){Thread.currentThread().interrupt();close();throw new IOException("Camera2 interrupted",e);}catch(Exception e){close();if(e instanceof IOException)throw (IOException)e;throw new IOException("Camera2 open failed",e);}
 }
 private void fail(String reason,int ticket){synchronized(lock){if(ticket==generation){failure=reason;lock.notifyAll();}}}
 private static Size chooseSize(Size[] choices,int width,int height)throws IOException{if(choices==null||choices.length==0)throw new IOException("No YUV_420_888 stream");Size best=null;double bestScore=Double.MAX_VALUE;for(Size size:choices){if(size.getWidth()>1920||size.getHeight()>1920)continue;double score=Math.abs(Math.log((double)size.getWidth()/size.getHeight()/((double)width/height)))*10+Math.abs(Math.log((double)size.getWidth()*size.getHeight()/((double)width*height)));if(score<bestScore){bestScore=score;best=size;}}if(best==null)throw new IOException("No bounded YUV source size <=1920");return best;}
 private static Range<Integer> chooseFps(Range<Integer>[] choices,int target){if(choices==null||choices.length==0)return null;Range<Integer> best=null;int score=Integer.MAX_VALUE;for(Range<Integer> r:choices){int value=r.getUpper()<=target?(target-r.getUpper())*100+(r.getUpper()-r.getLower()):10000+(r.getUpper()-target)*100+(r.getUpper()-r.getLower());if(value<score){score=value;best=r;}}return best;}
 @Override public int read(ByteBuffer output,long timeoutMs)throws IOException{if(output==null||!output.isDirect())throw new IOException("Direct output required");long deadline=SystemClock.elapsedRealtime()+Math.max(0,Math.min(5000,timeoutMs));synchronized(lock){if(config==null)throw new IOException("Camera2 not opened");if(output.capacity()<(long)config.width*config.height*2)throw new IOException("Output too small");while(!stopped&&!available&&failure.isEmpty()){long remaining=deadline-SystemClock.elapsedRealtime();if(remaining<=0)return 0;try{lock.wait(remaining);}catch(InterruptedException e){Thread.currentThread().interrupt();return 0;}}if(stopped)return -1;if(!failure.isEmpty())throw new IOException(failure);available=false;try(Image image=reader.acquireLatestImage()){if(image==null)return 0;long before=SystemClock.elapsedRealtimeNanos();int bytes=Camera2Converter.convert(image,output,config.width,config.height);convertNs+=SystemClock.elapsedRealtimeNanos()-before;if(bytes!=config.width*config.height*2)throw new IOException("YUV conversion failed: "+bytes);frames++;return bytes;}catch(IllegalStateException e){if(stopped)return -1;throw new IOException("ImageReader closed",e);}}}
 @Override public int[] format(){synchronized(lock){return config==null?null:new int[]{config.width,config.height,config.width*2,config.width*config.height*2,PACKED_UYVY};}}
 @Override public void stop(){CameraCaptureSession s;CameraDevice d;synchronized(lock){stopped=true;available=false;s=session;d=device;lock.notifyAll();}if(s!=null)s.close();if(d!=null)d.close();}
 @Override public void close(){stop();HandlerThread t;synchronized(lock){generation++;if(reader!=null){reader.close();reader=null;}session=null;device=null;t=thread;thread=null;handler=null;}if(t!=null)t.quitSafely();}
 @Override public String info(){synchronized(lock){return description+String.format(Locale.US,"; converted=%d meanJNI=%.2fms",frames,frames==0?0:convertNs/1000000.0/frames)+(failure.isEmpty()?"":"; "+failure);}}
}
