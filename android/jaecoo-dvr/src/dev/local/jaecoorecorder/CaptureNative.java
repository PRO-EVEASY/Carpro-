package dev.local.jaecoorecorder;
import java.nio.ByteBuffer;
public final class CaptureNative {
 private CaptureNative(){}
 public static native boolean prepareShared(ByteBuffer b);
 public static native void setSlotState(ByteBuffer b,int slot,int state,String message);
 public static native void beginSession();
 public static native void endSession();
 public static native int[] getAvailableInputs();
 public static native long openStream(int input);
 public static native int[] getStreamFormat(long handle);
 public static native int readStream(long handle,byte[] b);
 public static native int readStreamDirect(long handle,ByteBuffer b);
 public static native int readAndPublish(long h,ByteBuffer b,int slot);
 public static native void stopStream(long handle);
 public static native void closeStream(long handle);
 public static native String getLastError();
}
