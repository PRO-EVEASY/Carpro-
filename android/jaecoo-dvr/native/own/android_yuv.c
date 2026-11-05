#include <jni.h>
#include <stdint.h>
#include <stddef.h>

/* Independent YUV_420_888 -> packed UYVY nearest-neighbor center crop.
 * Handles padded rows and planar/interleaved chroma through explicit strides.
 * No third-party camera SDK, vehicle access, file I/O or dynamic dependency. */
JNIEXPORT jint JNICALL Java_dev_local_jaecoorecorder_Camera2Converter_convertPlanes(
 JNIEnv *env,jclass klass,jobject jy,jobject ju,jobject jv,jobject jout,jintArray specification){
 (void)klass;
 if(!jy||!ju||!jv||!jout||!specification||(*env)->GetArrayLength(env,specification)!=14)return -1;
 jint s[14];(*env)->GetIntArrayRegion(env,specification,0,14,s);if((*env)->ExceptionCheck(env))return -1;
 uint8_t *y=(*env)->GetDirectBufferAddress(env,jy),*u=(*env)->GetDirectBufferAddress(env,ju),*v=(*env)->GetDirectBufferAddress(env,jv),*out=(*env)->GetDirectBufferAddress(env,jout);
 jlong yc=(*env)->GetDirectBufferCapacity(env,jy),uc=(*env)->GetDirectBufferCapacity(env,ju),vc=(*env)->GetDirectBufferCapacity(env,jv),oc=(*env)->GetDirectBufferCapacity(env,jout);
 if(!y||!u||!v||!out||yc<=0||uc<=0||vc<=0||oc<=0)return -1;
 int yr=s[0],yp=s[1],ur=s[2],up=s[3],vr=s[4],vp=s[5],sw=s[6],sh=s[7],left=s[8],top=s[9],cw=s[10],ch=s[11],ow=s[12],oh=s[13];
 if(yr<=0||ur<=0||vr<=0||yp<1||yp>4||up<1||up>4||vp<1||vp>4||sw<2||sh<2||sw>8192||sh>8192||left<0||top<0||cw<2||ch<2||left>sw-cw||top>sh-ch||ow<2||oh<2||(ow&1)||ow>4096||oh>2160)return -1;
 int64_t bytes=(int64_t)ow*oh*2;if(bytes>oc||bytes>INT32_MAX)return -1;
 /* Preserve source aspect ratio by cropping, rather than stretching circles. */
 if((int64_t)cw*oh>(int64_t)ch*ow){int next=(int)((int64_t)ch*ow/oh);left+=(cw-next)/2;cw=next;}
 else if((int64_t)cw*oh<(int64_t)ch*ow){int next=(int)((int64_t)cw*oh/ow);top+=(ch-next)/2;ch=next;}
 if(cw<2||ch<2)return -1;
 int maxx=left+cw-1,maxy=top+ch-1;
 if((int64_t)maxy*yr+(int64_t)maxx*yp>=yc||(int64_t)(maxy/2)*ur+(int64_t)(maxx/2)*up>=uc||(int64_t)(maxy/2)*vr+(int64_t)(maxx/2)*vp>=vc)return -1;
 for(int row=0;row<oh;row++){
  int sy=top+(int)((int64_t)row*ch/oh);const uint8_t *yy=y+(int64_t)sy*yr,*uu=u+(int64_t)(sy/2)*ur,*vv=v+(int64_t)(sy/2)*vr;uint8_t *dst=out+(int64_t)row*ow*2;
  for(int col=0;col<ow;col+=2){int x0=left+(int)((int64_t)col*cw/ow),x1=left+(int)((int64_t)(col+1)*cw/ow),cx=((x0+x1)/2)/2;dst[0]=uu[(int64_t)cx*up];dst[1]=yy[(int64_t)x0*yp];dst[2]=vv[(int64_t)cx*vp];dst[3]=yy[(int64_t)x1*yp];dst+=4;}
 }
 return (jint)bytes;
}
