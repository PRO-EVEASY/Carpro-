/* Optional JNI entry for the existing application API. Independently written. */
#include <jni.h>
#include "own_mosaic.h"

JNIEXPORT jboolean JNICALL Java_dev_local_jaecoorecorder_CameraNative_composeMosaicToYuv420(
        JNIEnv *env, jclass type, jbyteArray a, jbyteArray b,
        jbyteArray c, jbyteArray d, jobject output, jboolean semi_planar,
        jint width, jint height, jint output_width, jint output_height,
        jintArray formats) {
    (void)type;
    if (!a || !b || !c || !d || !output || !formats ||
        width <= 0 || height <= 0 || width > 4096 || height > 4096 ||
        (*env)->GetArrayLength(env, formats) < 4) return JNI_FALSE;
    jlong capacity = (*env)->GetDirectBufferCapacity(env, output);
    uint8_t *destination = (*env)->GetDirectBufferAddress(env, output);
    if (!destination || capacity <= 0) return JNI_FALSE;
    jint ids[4];
    (*env)->GetIntArrayRegion(env, formats, 0, 4, ids);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    jbyteArray arrays[4] = {a,b,c,d};
    jbyte *bytes[4] = {0,0,0,0};
    jm_frame frame[4];
    int acquired = 0, success = 0;
    for (int i=0; i<4; i++) {
        jm_pixel_format format;
        if (!jm_format_from_qcarcam_id((uint32_t)ids[i], &format)) goto release;
        jsize length = (*env)->GetArrayLength(env, arrays[i]);
        if ((uint64_t)(uint32_t)width*(uint32_t)height*2u > (uint64_t)length) goto release;
        bytes[i] = (*env)->GetByteArrayElements(env, arrays[i], 0);
        if (!bytes[i]) goto release;
        acquired++;
        frame[i].data = (const uint8_t *)bytes[i];
        frame[i].size = (size_t)length;
        frame[i].width = (uint32_t)width;
        frame[i].height = (uint32_t)height;
        frame[i].stride = (uint32_t)width*2u;
        frame[i].format = format;
    }
    success = jm_compose_mosaic(frame,destination,(size_t)capacity,
                    (uint32_t)output_width,(uint32_t)output_height,
                    semi_planar?JM_LAYOUT_NV12:JM_LAYOUT_I420,0,0);
release:
    for (int i=0;i<acquired;i++) (*env)->ReleaseByteArrayElements(env,arrays[i],bytes[i],JNI_ABORT);
    return success?JNI_TRUE:JNI_FALSE;
}
