#include <jni.h>
#include "own_lens.h"
JNIEXPORT jboolean JNICALL Java_dev_local_jaecoorecorder_LensProfile_composeCorrected(JNIEnv *e,jclass type,jbyteArray a,jbyteArray b,jbyteArray c,jbyteArray d,jobject output,jboolean semi,jint iw,jint ih,jint ow,jint oh,jfloatArray profiles){
 (void)type;if(!a||!b||!c||!d||!output||!profiles||(*e)->GetArrayLength(e,profiles)!=32||iw<2||ih<2||iw>4096||ih>4096)return JNI_FALSE;
 uint8_t *destination=(*e)->GetDirectBufferAddress(e,output);jlong capacity=(*e)->GetDirectBufferCapacity(e,output);if(!destination||capacity<0)return JNI_FALSE;
 jbyteArray arrays[4]={a,b,c,d};jbyte *bytes[4]={0};const uint8_t *frames[4]={0};float values[32];(*e)->GetFloatArrayRegion(e,profiles,0,32,values);if((*e)->ExceptionCheck(e))return JNI_FALSE;
 size_t frame_capacity=(size_t)iw*(size_t)ih*2;int acquired=0,result=0;
 for(int i=0;i<4;i++){if((size_t)(*e)->GetArrayLength(e,arrays[i])<frame_capacity)goto release;bytes[i]=(*e)->GetByteArrayElements(e,arrays[i],NULL);if(!bytes[i])goto release;frames[i]=(const uint8_t*)bytes[i];acquired++;}
 result=own_lens_compose(frames,frame_capacity,destination,(size_t)capacity,semi,iw,ih,ow,oh,values);
 release:for(int i=0;i<acquired;i++)(*e)->ReleaseByteArrayElements(e,arrays[i],bytes[i],JNI_ABORT);return result?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_local_jaecoorecorder_LensProfile_clearCache(JNIEnv *e,jclass type){(void)e;(void)type;own_lens_clear_cache();}
