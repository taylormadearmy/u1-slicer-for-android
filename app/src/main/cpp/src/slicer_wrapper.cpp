#include "../include/sapil.h"
// =============================================================================
// slicer_wrapper.cpp — Main JNI entry points
// =============================================================================

static sapil::SlicerEngine* g_engine = nullptr;

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    SAPIL_LOGI("SAPIL JNI_OnLoad — initializing engine");
    g_engine = new sapil::SlicerEngine();
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    SAPIL_LOGI("SAPIL JNI_OnUnload — destroying engine");
    delete g_engine;
    g_engine = nullptr;
}

// ---- Version ----
JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_getCoreVersion(JNIEnv* env, jobject /* this */) {
    if (!g_engine) {
        return env->NewStringUTF("Engine not initialized");
    }
    std::string version = g_engine->getCoreVersion();
    return env->NewStringUTF(version.c_str());
}

// ---- Model Loading ----
JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_loadModel(JNIEnv* env, jobject, jstring jpath) {
    if (!g_engine) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    bool result = g_engine->loadModel(std::string(path));
    env->ReleaseStringUTFChars(jpath, path);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_NativeLibrary_clearModel(JNIEnv* env, jobject) {
    if (g_engine) g_engine->clearModel();
}

// ---- Model Info ----
JNIEXPORT jobject JNICALL
Java_com_u1_slicer_NativeLibrary_getModelInfo(JNIEnv* env, jobject) {
    if (!g_engine) return nullptr;
    sapil::ModelInfo info = g_engine->getModelInfo();
    return sapil::modelInfoToJava(env, info);
}

// ---- Slicing ----
JNIEXPORT jobject JNICALL
Java_com_u1_slicer_NativeLibrary_slice(JNIEnv* env, jobject thiz, jobject jconfig) {
    if (!g_engine) return nullptr;

    sapil::SliceConfig config = sapil::configFromJava(env, jconfig);

    // Store JNI references for callback
    JavaVM* jvm;
    env->GetJavaVM(&jvm);
    jobject globalThiz = env->NewGlobalRef(thiz);

    sapil::ProgressCallback progress = [jvm, globalThiz](int pct, const std::string& stage) {
        JNIEnv* cbEnv;
        bool attached = false;
        if (jvm->GetEnv((void**)&cbEnv, JNI_VERSION_1_6) != JNI_OK) {
            jvm->AttachCurrentThread(&cbEnv, nullptr);
            attached = true;
        }

        jclass cls = cbEnv->GetObjectClass(globalThiz);
        jmethodID mid = cbEnv->GetMethodID(cls, "onSliceProgress", "(ILjava/lang/String;)V");
        if (mid) {
            jstring jstage = cbEnv->NewStringUTF(stage.c_str());
            cbEnv->CallVoidMethod(globalThiz, mid, pct, jstage);
            cbEnv->DeleteLocalRef(jstage);
        }
        cbEnv->DeleteLocalRef(cls);

        if (attached) {
            jvm->DetachCurrentThread();
        }
    };

    sapil::SliceResult result = g_engine->slice(config, progress);

    env->DeleteGlobalRef(globalThiz);
    return sapil::sliceResultToJava(env, result);
}

// ---- Profile ----
JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_loadProfile(JNIEnv* env, jobject, jstring jpath) {
    if (!g_engine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    bool result = g_engine->loadProfile(std::string(path));
    env->ReleaseStringUTFChars(jpath, path);
    return result ? JNI_TRUE : JNI_FALSE;
}

// ---- G-code Preview ----
JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_getGcodePreview(JNIEnv* env, jobject, jint maxLines) {
    if (!g_engine) return env->NewStringUTF("");
    std::string preview = g_engine->getGcodePreview(maxLines);
    return env->NewStringUTF(preview.c_str());
}

// ---- Multiple Copies ----
// positions: flat float array [x0, y0, x1, y1, ...] in mm (bed-space)
JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_setModelInstances(JNIEnv* env, jobject, jfloatArray jpositions) {
    if (!g_engine) return JNI_FALSE;
    if (!jpositions) return JNI_FALSE;

    jsize len = env->GetArrayLength(jpositions);
    if (len == 0 || len % 2 != 0) return JNI_FALSE;

    jfloat* data = env->GetFloatArrayElements(jpositions, nullptr);

    std::vector<std::pair<float, float>> positions;
    positions.reserve(len / 2);
    for (int i = 0; i + 1 < len; i += 2) {
        positions.push_back({data[i], data[i + 1]});
    }

    env->ReleaseFloatArrayElements(jpositions, data, JNI_ABORT);

    bool result = g_engine->setModelInstances(positions);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_setModelScale(JNIEnv* env, jobject, jfloat x, jfloat y, jfloat z) {
    if (!g_engine) return JNI_FALSE;
    bool result = g_engine->setModelScale(x, y, z);
    return result ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
