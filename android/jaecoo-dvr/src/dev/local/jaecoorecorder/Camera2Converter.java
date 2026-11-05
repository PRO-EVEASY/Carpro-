package dev.local.jaecoorecorder;
import android.graphics.Rect;
import android.media.Image;
import java.nio.ByteBuffer;
/** JNI conversion uses independently built cameraadapter; no vendor camera library is required. */
public final class Camera2Converter {
 static{System.loadLibrary("cameraadapter");}
 private Camera2Converter(){}
 public static int convert(Image image,ByteBuffer output,int width,int height){Image.Plane[] p=image.getPlanes();if(p.length!=3)return -1;Rect crop=image.getCropRect();int[] spec={p[0].getRowStride(),p[0].getPixelStride(),p[1].getRowStride(),p[1].getPixelStride(),p[2].getRowStride(),p[2].getPixelStride(),image.getWidth(),image.getHeight(),crop.left,crop.top,crop.width(),crop.height(),width,height};return convertPlanes(p[0].getBuffer().slice(),p[1].getBuffer().slice(),p[2].getBuffer().slice(),output,spec);}
 static native int convertPlanes(ByteBuffer y,ByteBuffer u,ByteBuffer v,ByteBuffer output,int[] spec);
}
