#include <jni.h>
#include <stdint.h>
#include "own_camera.h"
JNIEXPORT void JNICALL Java_dev_local_jaecoorecorder_CaptureNative_beginSession(JNIEnv *e,jclass c){(void)e;(void)c;own_camera_begin();}
JNIEXPORT void JNICALL Java_dev_local_jaecoorecorder_CaptureNative_endSession(JNIEnv *e,jclass c){(void)e;(void)c;own_camera_end();}
JNIEXPORT jintArray JNICALL Java_dev_local_jaecoorecorder_CaptureNative_getAvailableInputs(JNIEnv *e,jclass c){(void)c;int inputs[16];int count=own_camera_inputs(inputs,16);if(count<0||count>16)return NULL;jint values[16];for(int k=0;k<count;k++)values[k]=(jint)inputs[k];jintArray result=(*e)->NewIntArray(e,count);if(result&&count)(*e)->SetIntArrayRegion(e,result,0,count,values);return result;}
JNIEXPORT jlong JNICALL Java_dev_local_jaecoorecorder_CaptureNative_openStream(JNIEnv *e,jclass c,jint input){(void)e;(void)c;return (jlong)(intptr_t)own_camera_open(input);}
JNIEXPORT jintArray JNICALL Java_dev_local_jaecoorecorder_CaptureNative_getStreamFormat(JNIEnv *e,jclass c,jlong h){(void)c;if(!h)return NULL;jint data[5]={1280,960,2560,2457600,117965058};jintArray a=(*e)->NewIntArray(e,5);if(a)(*e)->SetIntArrayRegion(e,a,0,5,data);return a;}
JNIEXPORT jint JNICALL Java_dev_local_jaecoorecorder_CaptureNative_readStream(JNIEnv *e,jclass c,jlong h,jbyteArray bytes){(void)c;if(!h||!bytes)return -1;jsize length=(*e)->GetArrayLength(e,bytes);jbyte *data=(*e)->GetByteArrayElements(e,bytes,NULL);if(!data)return -1;int result=own_camera_read((own_camera*)(intptr_t)h,data,length);(*e)->ReleaseByteArrayElements(e,bytes,data,result>0?0:JNI_ABORT);return result;}
JNIEXPORT void JNICALL Java_dev_local_jaecoorecorder_CaptureNative_stopStream(JNIEnv *e,jclass c,jlong h){(void)e;(void)c;own_camera_stop((own_camera*)(intptr_t)h);}
JNIEXPORT void JNICALL Java_dev_local_jaecoorecorder_CaptureNative_closeStream(JNIEnv *e,jclass c,jlong h){(void)e;(void)c;own_camera_close((own_camera*)(intptr_t)h);}
JNIEXPORT jstring JNICALL Java_dev_local_jaecoorecorder_CaptureNative_getLastError(JNIEnv *e,jclass c){(void)c;return (*e)->NewStringUTF(e,own_camera_error());}

JNIEXPORT jint JNICALL Java_dev_local_jaecoorecorder_CaptureNative_readStreamDirect(JNIEnv *e,jclass c,jlong h,jobject bytes){(void)c;if(!h||!bytes)return -1;void *data=(*e)->GetDirectBufferAddress(e,bytes);jlong length=(*e)->GetDirectBufferCapacity(e,bytes);if(!data||length<2457600||length>67108864)return -1;return own_camera_read((own_camera*)(intptr_t)h,data,(int)length);}
