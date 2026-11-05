package dev.local.jaecoorecorder;
import java.io.IOException;
import java.nio.ByteBuffer;
/** Vehicle-specific adapter. The service owns the shared CaptureNative begin/end session. */
public final class HabBackend implements CaptureBackend {
 private volatile long handle;
 private volatile boolean stopped=true;
 private int[] streamFormat;
 private String description="HAB כבוי";
 @Override public synchronized void open(Config config)throws IOException{if(handle!=0)throw new IOException("HAB already open");long value=CaptureNative.openStream(config.input);if(value==0)throw new IOException(CaptureNative.getLastError());int[] f=CaptureNative.getStreamFormat(value);if(f==null||f.length!=5||f[0]<=0||f[1]<=0||f[3]<=0){CaptureNative.closeStream(value);throw new IOException("Invalid HAB format");}if(f[0]!=config.width||f[1]!=config.height){CaptureNative.closeStream(value);throw new IOException("HAB source size is fixed: "+f[0]+"x"+f[1]);}handle=value;streamFormat=f.clone();stopped=false;description="HAB input="+config.input+" "+f[0]+"x"+f[1]+" UYVY";}
 @Override public int read(ByteBuffer output,long timeoutMs)throws IOException{long value=handle;if(value==0||stopped)return -1;if(output==null||!output.isDirect()||output.capacity()<streamFormat[3])throw new IOException("Direct frame buffer too small");if(timeoutMs<=0)return 0;int n=CaptureNative.readStreamDirect(value,output);if(stopped)return -1;if(n<0)throw new IOException(CaptureNative.getLastError());return n;}
 @Override public synchronized int[] format(){return streamFormat==null?null:streamFormat.clone();}
 @Override public void stop(){stopped=true;long value=handle;if(value!=0)CaptureNative.stopStream(value);}
 /** Call after the owning read thread has exited, as with the original native API. */
 @Override public synchronized void close(){stop();long value=handle;handle=0;if(value!=0)CaptureNative.closeStream(value);description="HAB כבוי";}
 @Override public String info(){return description+"; הזמן הקצוב נקבע על ידי מנהל ההתקן המקורי";}
}
