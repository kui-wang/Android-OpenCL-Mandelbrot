#ifndef __JNI_H__
#define __JNI_H__
#ifdef __cplusplus

#include <android/bitmap.h>
#include <android/log.h>

#define app_name "OpenCLviaJNI"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, app_name, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, app_name, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, app_name, __VA_ARGS__))
extern "C" {
#endif

#define DECLARE_NOPARAMS(returnType,fullClassName,func) \
JNIEXPORT returnType JNICALL Java_##fullClassName##_##func(JNIEnv *env, jclass clazz);

#define DECLARE_WITHPARAMS(returnType,fullClassName,func,...) \
JNIEXPORT returnType JNICALL Java_##fullClassName##_##func(JNIEnv *env, jclass clazz,__VA_ARGS__);

DECLARE_WITHPARAMS(jint,com_example_MandelbrotOpenCLActivity,mandelbrot,jshortArray shortColor, jint width, jint height)

#ifdef __cplusplus
}
#endif

#endif //__JNI_H__
