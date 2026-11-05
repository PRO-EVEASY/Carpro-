package dev.local.jaecoorecorder;
import java.io.IOException;
import java.nio.ByteBuffer;
/** A camera source that only reads images. Output is direct-buffer packed UYVY. */
public interface CaptureBackend extends AutoCloseable {
 int PACKED_UYVY=117965058;
 final class Config {
  public final int slot,input,width,height,targetFps;
  public final String cameraId;
  public Config(int slot,int input,String cameraId,int width,int height,int targetFps){if(width<2||height<2||(width&1)!=0||width>4096||height>2160)throw new IllegalArgumentException("Invalid output size");this.slot=slot;this.input=input;this.cameraId=cameraId;this.width=width;this.height=height;this.targetFps=targetFps<=0?25:Math.max(1,Math.min(30,targetFps));}
 }
 void open(Config configuration)throws IOException;
 /** bytes on success, 0 on timeout, -1 when stopped. Caller owns output and may reuse it after return. */
 int read(ByteBuffer output,long timeoutMs)throws IOException;
 /** width,height,row-stride,frame-bytes,packed-UYVY-format */
 int[] format();
 void stop();
 @Override void close();
 default int frameRateLimit(){return 25;}
 String info();
}
