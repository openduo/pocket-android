// Copyright 2026 OpenDuo
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

#include <jni.h>
#include <stdint.h>
#include <opus.h>

JNIEXPORT jlong JNICALL Java_dev_openduo_pocket_audio_OpusEncoder_create(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP, &error);
    if (!encoder || error != OPUS_OK) return 0;
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(0));
    opus_encoder_ctl(encoder, OPUS_SET_VBR(1));
    opus_encoder_ctl(encoder, OPUS_SET_DTX(0));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(0));
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jbyteArray JNICALL Java_dev_openduo_pocket_audio_OpusEncoder_encodeNative(JNIEnv *env, jclass cls, jlong handle, jshortArray input) {
    (void)cls;
    if (!handle || !input || (*env)->GetArrayLength(env, input) != 320) return NULL;
    jshort pcm[320]; (*env)->GetShortArrayRegion(env, input, 0, 320, pcm);
    unsigned char packet[1500]; int count = opus_encode((OpusEncoder *)(intptr_t)handle, pcm, 320, packet, sizeof(packet));
    if (count <= 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, count); if (!result) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, count, (const jbyte *)packet); return result;
}

JNIEXPORT void JNICALL Java_dev_openduo_pocket_audio_OpusEncoder_destroy(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; if (handle) opus_encoder_destroy((OpusEncoder *)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL Java_dev_openduo_pocket_audio_OpusDecoder_create(JNIEnv *env, jclass type) {
    (void)env; (void)type; int error;
    OpusDecoder *decoder = opus_decoder_create(16000, 1, &error);
    return error == OPUS_OK ? (jlong)(intptr_t)decoder : 0;
}
JNIEXPORT jshortArray JNICALL Java_dev_openduo_pocket_audio_OpusDecoder_decode(JNIEnv *env, jclass type, jlong handle, jbyteArray input) {
    (void)type;
    if (!handle || !input) return NULL;
    jsize length = (*env)->GetArrayLength(env, input);
    if (length < 1 || length > 1275) return NULL;
    unsigned char packet[1275]; (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)packet);
    opus_int16 samples[1920];
    int count = opus_decode((OpusDecoder *)(intptr_t)handle, packet, length, samples, 1920, 0);
    if (count < 0) return NULL;
    jshortArray result = (*env)->NewShortArray(env, count);
    if (result) (*env)->SetShortArrayRegion(env, result, 0, count, samples);
    return result;
}
JNIEXPORT void JNICALL Java_dev_openduo_pocket_audio_OpusDecoder_destroy(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    if (handle) opus_decoder_destroy((OpusDecoder *)(intptr_t)handle);
}
