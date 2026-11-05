package dev.local.jaecoorecorder;
import java.nio.ByteBuffer;
public final class NativeBridge {
 static {System.loadLibrary("cameraadapter");}
 private NativeBridge(){}
 public static native boolean init(Class<?> capture,Class<?> camera);
 public static native void yuv420ToArgb(ByteBuffer yuv,int width,int height,boolean semiPlanar,int[] argb);
 public static native String lastError();
}
