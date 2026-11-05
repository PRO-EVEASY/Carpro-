#include <jni.h>
#include <stdint.h>

JNIEXPORT jboolean JNICALL Java_dev_local_jaecoorecorder_NativeBridge_init(JNIEnv *env,jclass self,jclass capture,jclass camera) {
    /* JNI methods are direct exported symbols in our own adapter. */
    (void)env; (void)self; (void)capture; (void)camera;
    return JNI_TRUE;
}
JNIEXPORT jstring JNICALL Java_dev_local_jaecoorecorder_NativeBridge_lastError(JNIEnv *env,jclass self) {
    (void)self;return (*env)->NewStringUTF(env,"");
}
static inline int clamp(int a) {return a<0?0:a>255?255:a;}
JNIEXPORT void JNICALL Java_dev_local_jaecoorecorder_NativeBridge_yuv420ToArgb(JNIEnv *env,jclass self,jobject source,jint width,jint height,jboolean semi,jintArray target) {
    (void)self;
    if(width<=0||height<=0||(width&1)||(height&1)||width>4096||height>4096||!target||!source)return;
    size_t pixels=(size_t)width*height;
    if((*env)->GetArrayLength(env,target)<(jsize)pixels||(*env)->GetDirectBufferCapacity(env,source)<(jlong)(pixels*3/2))return;
    const uint8_t *y=(*env)->GetDirectBufferAddress(env,source);
    if(!y)return;
    jint *out=(*env)->GetIntArrayElements(env,target,NULL);
    if(!out)return;
    for(int row=0;row<height;row++) {
        size_t yr=(size_t)row*width,uvr=(size_t)(row/2)*(width/2);
        for(int col=0;col<width;col++) {
            size_t uv=uvr+col/2;
            int u=(int)y[pixels+(semi?uv*2:uv)]-128;
            int v=(int)y[pixels+(semi?uv*2+1:pixels/4+uv)]-128;
            int c=(int)y[yr+col]-16;if(c<0)c=0;
            int r=clamp((298*c+409*v+128)>>8);
            int g=clamp((298*c-100*u-208*v+128)>>8);
            int b=clamp((298*c+516*u+128)>>8);
            out[yr+col]=(jint)(0xff000000u|((unsigned)r<<16)|((unsigned)g<<8)|(unsigned)b);
        }
    }
    (*env)->ReleaseIntArrayElements(env,target,out,0);
}
