// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>

/* The shipped firmware exports static JNI symbols. Forward their existing ABI without
 * relocating firmware code or declaring any vendor Java package in the application. */
static jlong (*engine_create)(JNIEnv *, jclass, jobject);
static void (*engine_destroy)(JNIEnv *, jclass, jlong);
static jint (*engine_init)(JNIEnv *, jclass, jlong, jobject);
static jint (*engine_start)(JNIEnv *, jclass, jlong, jboolean);
static jint (*engine_read)(JNIEnv *, jclass, jlong);
static jint (*engine_stop)(JNIEnv *, jclass, jlong);
static void (*driver_read_interval)(int);
static uint64_t (*capture_meter)(void);

static jlong create(JNIEnv *env, jclass type, jobject callback) { return engine_create(env, type, callback); }
static void destroy(JNIEnv *env, jclass type, jlong handle) { engine_destroy(env, type, handle); }
static jint init(JNIEnv *env, jclass type, jlong handle, jobject config) { return engine_init(env, type, handle, config); }
static jint start(JNIEnv *env, jclass type, jlong handle, jboolean device) { return engine_start(env, type, handle, device); }
static jint read_audio(JNIEnv *env, jclass type, jlong handle) { return engine_read(env, type, handle); }
static jint stop(JNIEnv *env, jclass type, jlong handle) { return engine_stop(env, type, handle); }
static jlong meter(JNIEnv *env, jclass type) { (void) env; (void) type; return (jlong) capture_meter(); }
static void set_read_interval(JNIEnv *env, jclass type, jint millis) {
    (void) type;
    if (millis < 100 || millis > 1000 || millis % 100 != 0) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (error) (*env)->ThrowNew(env, error, "Driver interval must be 100..1000ms in 100ms steps");
        return;
    }
    driver_read_interval(millis);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    /* ArrayRecorder loads the firmware through Java first so its JNI_OnLoad retains the VM. */
    void *engine = dlopen("libtr_recorder.so", RTLD_NOW | RTLD_LOCAL);
    if (engine == NULL) return JNI_ERR;
    void *alsa = dlopen("libtinyalsa.so", RTLD_NOW | RTLD_LOCAL);
    if (alsa == NULL) { dlclose(engine); return JNI_ERR; }
    capture_meter = (uint64_t (*)(void)) dlsym(alsa, "e2_capture_meter");
    dlclose(alsa);
    engine_create = (jlong (*)(JNIEnv *, jclass, jobject)) dlsym(engine, "Java_com_sogou_speech_recorder_TrPenMultipleRecorder_nativeCreateRecorder");
    engine_destroy = (void (*)(JNIEnv *, jclass, jlong)) dlsym(engine, "Java_com_sogou_speech_recorder_TrPenMultipleRecorder_nativeDestroyRecorder");
    engine_init = (jint (*)(JNIEnv *, jclass, jlong, jobject)) dlsym(engine, "Java_com_sogou_speech_recorder_TrPenMultipleRecorder_nativeInit");
    engine_start = (jint (*)(JNIEnv *, jclass, jlong, jboolean)) dlsym(engine, "Java_com_sogou_speech_recorder_TrPenMultipleRecorder_nativeStart");
    engine_read = (jint (*)(JNIEnv *, jclass, jlong)) dlsym(engine, "Java_com_sogou_speech_recorder_TrPenMultipleRecorder_nativeRead");
    engine_stop = (jint (*)(JNIEnv *, jclass, jlong)) dlsym(engine, "Java_com_sogou_speech_recorder_TrPenMultipleRecorder_nativeStop");
    driver_read_interval = (void (*)(int)) dlsym(engine, "sogou_speech_set_recorder_driver_read_interval");
    if (!engine_create || !engine_destroy || !engine_init || !engine_start || !engine_read || !engine_stop || !driver_read_interval || !capture_meter) {
        dlclose(engine);
        return JNI_ERR;
    }
    JNINativeMethod methods[] = {
        {"create", "(Ldev/openduo/pocket/device/e2/ArrayRecorder$Callback;)J", (void *) create},
        {"setReadInterval", "(I)V", (void *) set_read_interval},
        {"captureMeter", "()J", (void *) meter},
        {"destroy", "(J)V", (void *) destroy},
        {"init", "(JLdev/openduo/pocket/device/e2/ArrayRecorder$Config;)I", (void *) init},
        {"start", "(JZ)I", (void *) start},
        {"read", "(J)I", (void *) read_audio},
        {"stop", "(J)I", (void *) stop}
    };
    jclass type = (*env)->FindClass(env, "dev/openduo/pocket/device/e2/ArrayRecorder");
    if (type == NULL) { dlclose(engine); return JNI_ERR; }
    jint result = (*env)->RegisterNatives(env, type, methods, sizeof(methods) / sizeof(methods[0]));
    (*env)->DeleteLocalRef(env, type);
    if (result != JNI_OK) { dlclose(engine); return JNI_ERR; }
    return JNI_VERSION_1_6;
}
