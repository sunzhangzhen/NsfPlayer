#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "gme/gme.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "NsfCore", __VA_ARGS__)

static Music_Emu* g_emu = NULL;
static int g_rate = 44100;
static std::string g_path;

static void closeEmu() {
    if (g_emu) { gme_delete(g_emu); g_emu = NULL; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nsfplayer_NsfCore_nativeOpen(JNIEnv* env, jclass, jstring jpath, jint rate) {
    closeEmu();
    g_rate = rate > 0 ? rate : 44100;
    const char* p = env->GetStringUTFChars(jpath, NULL);
    g_path = p;
    env->ReleaseStringUTFChars(jpath, p);

    gme_err_t err = gme_open_file(g_path.c_str(), &g_emu, g_rate);
    if (err) { LOG("open failed: %s", err); g_emu = NULL; return -1; }
    return gme_track_count(g_emu);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nsfplayer_NsfCore_nativeTrackCount(JNIEnv*, jclass) {
    return g_emu ? gme_track_count(g_emu) : 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_nsfplayer_NsfCore_nativeTrackInfo(JNIEnv* env, jclass, jint index) {
    // returns String[]{song, game, author, copyright, lengthMs, introMs, loopMs}
    if (!g_emu) return NULL;
    gme_info_t* info = NULL;
    if (gme_track_info(g_emu, &info, index)) return NULL;
    jobjectArray arr = env->NewObjectArray(7, env->FindClass("java/lang/String"), NULL);
    const char* vals[7];
    char buf[64];
    vals[0] = info->song; vals[1] = info->game; vals[2] = info->author; vals[3] = info->copyright;
    snprintf(buf, sizeof(buf), "%ld", (long)info->length); vals[4] = buf;
    static char b2[32], b3[32];
    snprintf(b2, sizeof(b2), "%ld", (long)info->intro_length);
    snprintf(b3, sizeof(b3), "%ld", (long)info->loop_length);
    vals[5] = b2; vals[6] = b3;
    for (int i = 0; i < 7; i++) {
        jstring s = env->NewStringUTF(vals[i] ? vals[i] : "");
        env->SetObjectArrayElement(arr, i, s);
        env->DeleteLocalRef(s);
    }
    gme_free_info(info);
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nsfplayer_NsfCore_nativeStartTrack(JNIEnv*, jclass, jint index, jint fadeMs) {
    if (!g_emu) return -1;
    gme_err_t err = gme_start_track(g_emu, index);
    if (err) { LOG("startTrack failed: %s", err); return -2; }
    if (fadeMs > 0) gme_set_fade(g_emu, fadeMs);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nsfplayer_NsfCore_nativeRender(JNIEnv* env, jclass, jshortArray jbuf, jint frames) {
    if (!g_emu) return -1;
    jshort* buf = env->GetShortArrayElements(jbuf, NULL);
    // gme wants short*, count in samples per channel
    gme_err_t err = gme_play(g_emu, frames * 2, buf);
    env->ReleaseShortArrayElements(jbuf, buf, 0);
    if (err) { LOG("render err: %s", err); return -2; }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nsfplayer_NsfCore_nativeTrackEnded(JNIEnv*, jclass) {
    return (g_emu && gme_track_ended(g_emu)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nsfplayer_NsfCore_nativeTell(JNIEnv*, jclass) {
    return g_emu ? (jint)gme_tell(g_emu) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nsfplayer_NsfCore_nativeSeek(JNIEnv*, jclass, jint ms) {
    if (g_emu) gme_seek(g_emu, ms);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nsfplayer_NsfCore_nativeClose(JNIEnv*, jclass) {
    closeEmu();
}

