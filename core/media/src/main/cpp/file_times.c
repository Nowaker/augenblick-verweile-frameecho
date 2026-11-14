#include <errno.h>
#include <jni.h>
#include <sys/stat.h>
#include <time.h>

JNIEXPORT jint JNICALL
Java_com_shangjin_frameecho_core_media_export_FileDescriptorTimes_restoreNative(
    JNIEnv *env, jobject instance, jint fd, jlong seconds, jint nanoseconds) {
    (void)env;
    (void)instance;
    const time_t native_seconds = (time_t)seconds;
    if ((jlong)native_seconds != seconds) return EOVERFLOW;
    const struct timespec times[2] = {
        {.tv_sec = 0, .tv_nsec = UTIME_OMIT},
        {.tv_sec = native_seconds, .tv_nsec = nanoseconds}
    };
    return futimens(fd, times) == 0 ? 0 : errno;
}

JNIEXPORT jlongArray JNICALL
Java_com_shangjin_frameecho_core_media_export_FileDescriptorTimes_readNative(
    JNIEnv *env, jobject instance, jint fd) {
    (void)instance;
    struct stat attributes;
    jlong values[3] = {0, 0, 0};
    if (fstat(fd, &attributes) == 0) {
        values[0] = attributes.st_mtim.tv_sec;
        values[1] = attributes.st_mtim.tv_nsec;
    } else {
        values[2] = errno;
    }
    jlongArray result = (*env)->NewLongArray(env, 3);
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 3, values);
    return result;
}
