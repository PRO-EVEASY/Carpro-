package dev.local.jaecoorecorder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Static UV tessellation for a manual perspective-to-equidistant projection.
 * Rebuild only when the profile or input aspect ratio changes. */
public final class LensMesh {
 private LensMesh(){}
 public static FloatBuffer build(int columns,int rows,float[] profile,int inputWidth,int inputHeight,boolean corrected){
  if(columns<1||rows<1||columns>512||rows>512||inputWidth<2||inputHeight<2||inputWidth>4096||inputHeight>4096)throw new IllegalArgumentException("Invalid lens mesh dimensions");
  if(corrected)validate(profile);
  int count=Math.multiplyExact(Math.multiplyExact(columns,rows),24);
  FloatBuffer result=ByteBuffer.allocateDirect(Math.multiplyExact(count,4)).order(ByteOrder.nativeOrder()).asFloatBuffer();
  float tangent=corrected?(float)Math.tan(profile[4]*Math.PI/360):0;
  float aspect=(float)inputWidth/inputHeight;
  for(int row=0;row<rows;row++)for(int column=0;column<columns;column++){
   float left=(float)column/columns,right=(float)(column+1)/columns;
   float top=(float)row/rows,bottom=(float)(row+1)/rows;
   vertex(result,left,top,profile,tangent,aspect,corrected);
   vertex(result,left,bottom,profile,tangent,aspect,corrected);
   vertex(result,right,top,profile,tangent,aspect,corrected);
   vertex(result,right,top,profile,tangent,aspect,corrected);
   vertex(result,left,bottom,profile,tangent,aspect,corrected);
   vertex(result,right,bottom,profile,tangent,aspect,corrected);
  }
  result.flip();return result;
 }
 private static void vertex(FloatBuffer destination,float u,float v,float[] p,float tangent,float aspect,boolean corrected){
  destination.put(2*u-1).put(1-2*v);
  if(!corrected){destination.put(u).put(v);return;}
  double x=(2*(double)u-1)*tangent,y=(2*(double)v-1)*tangent/aspect;
  double radius=Math.sqrt(x*x+y*y),theta=Math.atan(radius),theta2=theta*theta;
  double scale=radius>1e-8?theta*(1+p[5]*theta2+p[6]*theta2*theta2)/radius:1;
  destination.put((float)(p[0]+p[2]*x*scale/p[7]));
  destination.put((float)(p[1]+p[3]*y*scale/p[7]));
 }
 private static void validate(float[] p){
  if(p==null||p.length!=8)throw new IllegalArgumentException("Lens profile requires eight values");
  for(float value:p)if(Float.isNaN(value)||Float.isInfinite(value))throw new IllegalArgumentException("Non-finite lens parameter");
  if(p[0]<0||p[0]>1||p[1]<0||p[1]>1||p[2]<.05f||p[2]>2||p[3]<.05f||p[3]>2||p[4]<30||p[4]>150||Math.abs(p[5])>1||Math.abs(p[6])>1||p[7]<.5f||p[7]>3)throw new IllegalArgumentException("Lens parameter outside supported range");
 }
}
