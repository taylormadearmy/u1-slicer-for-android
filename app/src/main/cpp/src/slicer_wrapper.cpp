#include "../include/sapil.h"
#include "sapil_diagnostics.h"
// =============================================================================
// slicer_wrapper.cpp — Main JNI entry points
// =============================================================================

static sapil::SlicerEngine* g_engine = nullptr;

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    SAPIL_LOGI("SAPIL JNI_OnLoad — initializing engine");
    sapil::diagnostics_record_native_event("native_library_loaded", "{}");
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

JNIEXPORT void JNICALL
Java_com_u1_slicer_NativeLibrary_configureDiagnostics(JNIEnv* env, jobject, jstring jpath) {
    if (!jpath) return;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    sapil::diagnostics_set_output_path(std::string(path));
    env->ReleaseStringUTFChars(jpath, path);
}

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_getDiagnosticsState(JNIEnv* env, jobject) {
    return env->NewStringUTF(sapil::diagnostics_get_state_json().c_str());
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

// Phase 1 sub-plan #2b: plate-aware model loader.
// Kotlin convention: plateIdx = -1 means "load all plates" (BBS plate_id=0).
// Kotlin plateIdx >= 0 means "load only plate N" (BBS plate_id = N+1, 1-based).
JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_loadModelForPlate(
        JNIEnv* env, jobject, jstring jpath, jint jplate_idx) {
    if (!g_engine) return JNI_FALSE;

    const int bbs_plate_id = (jplate_idx < 0) ? 0 : static_cast<int>(jplate_idx) + 1;

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    bool result = g_engine->loadModel(std::string(path), bbs_plate_id);
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

JNIEXPORT jobject JNICALL
Java_com_u1_slicer_NativeLibrary_getPreparePreviewMesh(JNIEnv* env, jobject, jint maxTriangles) {
    if (!g_engine) return nullptr;
    sapil::PreviewMesh mesh = g_engine->getPreparePreviewMesh(static_cast<int>(maxTriangles));
    if (mesh.extruder_indices.empty()) return nullptr;
    return sapil::previewMeshToJava(env, mesh);
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

// ---- Bambu Snapshot (Phase 0 diff harness) ----
JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeDumpBambuModel(JNIEnv* env, jobject, jstring jpath) {
    if (!g_engine) return nullptr;
    if (jpath == nullptr) return nullptr;

    // Re-load to guarantee a clean snapshot, independent of any prior
    // setModelRotation / setModelInstances mutations.
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    bool ok = g_engine->loadModel(std::string(path));
    env->ReleaseStringUTFChars(jpath, path);
    if (!ok) return nullptr;

    std::string json = sapil::bambu_snapshot_json();
    if (json.empty()) return nullptr;
    return env->NewStringUTF(json.c_str());
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

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_setModelRotation(
        JNIEnv*, jobject, jfloat rx, jfloat ry, jfloat rz) {
    if (!g_engine) return JNI_FALSE;
    bool result = g_engine->setModelRotation(rx, ry, rz);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_u1_slicer_NativeLibrary_getInstanceOffsets(
        JNIEnv* env, jobject) {
    if (!g_engine) return env->NewFloatArray(0);
    auto offsets = g_engine->getInstanceOffsets();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(offsets.size()));
    if (!offsets.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(offsets.size()), offsets.data());
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_u1_slicer_NativeLibrary_getInstanceWorldZMins(
        JNIEnv* env, jobject) {
    if (!g_engine) return env->NewFloatArray(0);
    auto zmins = g_engine->getInstanceWorldZMins();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(zmins.size()));
    if (!zmins.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(zmins.size()), zmins.data());
    }
    return result;
}

// ---- Additive model loading ----
JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_addModel(JNIEnv* env, jobject, jstring jpath) {
    if (!g_engine) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    bool result = g_engine->addModel(std::string(path));
    env->ReleaseStringUTFChars(jpath, path);
    return result ? JNI_TRUE : JNI_FALSE;
}

// F85: add a specific plate of a 3MF into the existing model.
// plateIdx = -1 → all plates (same as addModel); plateIdx >= 0 → 1-based BBS plate_id = plateIdx+1.
JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_addModelForPlate(JNIEnv* env, jobject, jstring jpath, jint jplate_idx) {
    if (!g_engine) return JNI_FALSE;
    const int plate_id = (jplate_idx < 0) ? 0 : static_cast<int>(jplate_idx) + 1;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    bool result = g_engine->addModel(std::string(path), plate_id);
    env->ReleaseStringUTFChars(jpath, path);
    return result ? JNI_TRUE : JNI_FALSE;
}

// Returns flat [sizeX0, sizeY0, sizeZ0, sizeX1, sizeY1, sizeZ1, ...] for all objects.
JNIEXPORT jfloatArray JNICALL
Java_com_u1_slicer_NativeLibrary_getObjectBoundingBoxes(JNIEnv* env, jobject) {
    if (!g_engine) return env->NewFloatArray(0);
    auto boxes = g_engine->getObjectBoundingBoxes();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(boxes.size()));
    if (!boxes.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(boxes.size()), boxes.data());
    }
    return result;
}

// positions: flat [x0, y0, x1, y1, ...] in mm — one lower-left corner per object.
JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_setObjectPositions(JNIEnv* env, jobject, jfloatArray jpositions) {
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
    bool result = g_engine->setObjectPositions(positions);
    return result ? JNI_TRUE : JNI_FALSE;
}

// ---- Cancellation ----
JNIEXPORT void JNICALL
Java_com_u1_slicer_NativeLibrary_cancelPreviewMesh(JNIEnv*, jobject) {
    sapil::SlicerEngine::cancelPreviewMesh();
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_NativeLibrary_cancelSlice(JNIEnv*, jobject) {
    sapil::SlicerEngine::cancelSlice();
}

// =============================================================================
// F66 — Split + Auto-Orient + per-object pose JNI bridges
// =============================================================================

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeIsObjectSplittable(JNIEnv*, jobject, jint objIdx) {
    return (g_engine && g_engine->isObjectSplittable(objIdx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeIsVolumeSplittable(JNIEnv*, jobject, jint objIdx, jint volIdx) {
    return (g_engine && g_engine->isVolumeSplittable(objIdx, volIdx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSplitObject(JNIEnv* env, jobject, jint objIdx) {
    if (!g_engine) return nullptr;
    auto res = g_engine->splitObject(objIdx);
    if (!res) return nullptr;
    jintArray out = env->NewIntArray(2);
    jint vals[2] = { res->removedIdx, res->addedCount };
    env->SetIntArrayRegion(out, 0, 2, vals);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSplitVolume(JNIEnv*, jobject, jint objIdx, jint volIdx) {
    if (!g_engine) return -1;
    return g_engine->splitVolume(objIdx, volIdx);
}

JNIEXPORT jdoubleArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeAutoOrientObject(JNIEnv* env, jobject, jint objIdx) {
    if (!g_engine) return nullptr;
    auto res = g_engine->autoOrientObject(objIdx);
    if (!res) return nullptr;
    jdoubleArray out = env->NewDoubleArray(3);
    jdouble vals[3] = { (*res)[0], (*res)[1], (*res)[2] };
    env->SetDoubleArrayRegion(out, 0, 3, vals);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeAutoOrientAll(JNIEnv*, jobject) {
    if (!g_engine) return 0;
    return g_engine->autoOrientAll();
}

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSetObjectRotation(
        JNIEnv*, jobject, jint objIdx, jfloat x, jfloat y, jfloat z) {
    return (g_engine && g_engine->setObjectRotation(objIdx, x, y, z)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectRotation(JNIEnv* env, jobject, jint objIdx) {
    jfloatArray out = env->NewFloatArray(3);
    if (!g_engine) {
        jfloat zeros[3] = { 0.f, 0.f, 0.f };
        env->SetFloatArrayRegion(out, 0, 3, zeros);
        return out;
    }
    auto r = g_engine->getObjectRotation(objIdx);
    jfloat vals[3] = { r[0], r[1], r[2] };
    env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSetObjectScale(
        JNIEnv*, jobject, jint objIdx, jfloat sx, jfloat sy, jfloat sz) {
    return (g_engine && g_engine->setObjectScale(objIdx, sx, sy, sz)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectScale(JNIEnv* env, jobject, jint objIdx) {
    jfloatArray out = env->NewFloatArray(3);
    if (!g_engine) {
        jfloat ones[3] = { 1.f, 1.f, 1.f };
        env->SetFloatArrayRegion(out, 0, 3, ones);
        return out;
    }
    auto s = g_engine->getObjectScale(objIdx);
    jfloat vals[3] = { s[0], s[1], s[2] };
    env->SetFloatArrayRegion(out, 0, 3, vals);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectName(JNIEnv* env, jobject, jint objIdx) {
    if (!g_engine) return env->NewStringUTF("");
    return env->NewStringUTF(g_engine->getObjectName(objIdx).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeName(JNIEnv* env, jobject, jint objIdx, jint volIdx) {
    if (!g_engine) return env->NewStringUTF("");
    return env->NewStringUTF(g_engine->getVolumeName(objIdx, volIdx).c_str());
}

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeExtruder(
        JNIEnv*, jobject, jint objIdx, jint volIdx) {
    return g_engine ? g_engine->getVolumeExtruder(objIdx, volIdx) : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_NativeLibrary_nativeSetVolumeExtruder(
        JNIEnv*, jobject, jint objIdx, jint volIdx, jint slot) {
    return (g_engine && g_engine->setVolumeExtruder(objIdx, volIdx, slot)) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
