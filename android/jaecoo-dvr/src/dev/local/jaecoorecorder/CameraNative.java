package dev.local.jaecoorecorder;
import java.nio.ByteBuffer;
public final class CameraNative {
 private CameraNative(){}
 public static native void beginSession();
 public static native void closeCapture();
 public static native void closeStream(long handle);
 public static native boolean composeMosaicToYuv420(byte[] a,byte[] b,byte[] c,byte[] d,ByteBuffer out,boolean semiPlanar,int width,int height,int outputWidth,int outputHeight,int[] formats);
 public static native boolean configureCapture(String value);
 public static native String getLastError();
 public static native long openStream(int input);
 public static native int readStream(long handle,byte[] b);
 public static native void resetCaptureState();
 public static native void stopStream(long handle);
}
