#include <jni.h>
#include <stdlib.h>

#include "phonemize.h"

JNIEXPORT jboolean JNICALL Java_com_pedant_app_Phonemizer_nativeInit(JNIEnv *env, jobject self, jstring dataDir) {
  const char *dir = (*env)->GetStringUTFChars(env, dataDir, NULL);
  int ok = pedant_espeak_init(dir);
  (*env)->ReleaseStringUTFChars(env, dataDir, dir);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_pedant_app_Phonemizer_nativePhonemize(JNIEnv *env, jobject self, jstring voice,
                                                                        jstring text) {
  const char *v = (*env)->GetStringUTFChars(env, voice, NULL);
  const char *t = (*env)->GetStringUTFChars(env, text, NULL);
  char *ipa = pedant_phonemize(v, t);
  (*env)->ReleaseStringUTFChars(env, voice, v);
  (*env)->ReleaseStringUTFChars(env, text, t);
  if (!ipa) return NULL;
  jstring result = (*env)->NewStringUTF(env, ipa);
  free(ipa);
  return result;
}
