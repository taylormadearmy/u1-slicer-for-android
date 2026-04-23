// sapil_bambu_volumes.cpp
//
// Phase 1 sub-plan #1: JNI accessors for walking g_model's objects + volumes.
// Pure reads — no globals, no allocations beyond returned jarrays. Callers
// hold NativeLibrary.previewMutex on the Kotlin side; C++ assumes serialised
// access. Phase 0 made g_model non-static externally linkable in sapil_model.cpp;
// every global here `extern`s from there.

#include <jni.h>

#include <map>
#include <vector>

#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleSelector.hpp"

#include "sapil_bambu_snapshot.h"  // for sapil::count_paint_states

namespace sapil {
// Provided by sapil_model.cpp.
extern Slic3r::Model g_model;
} // namespace sapil

extern "C" {

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectCount(JNIEnv*, jobject) {
    return static_cast<jint>(sapil::g_model.objects.size());
}

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeCount(
        JNIEnv*, jobject, jint objectIndex) {
    if (objectIndex < 0) return 0;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return 0;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return 0;
    return static_cast<jint>(mo->volumes.size());
}

JNIEXPORT jlong JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectModelId(
        JNIEnv*, jobject, jint objectIndex) {
    if (objectIndex < 0) return 0;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return 0;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return 0;
    return static_cast<jlong>(mo->id().id);
}

JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetVolumeScalars(
        JNIEnv* env, jobject, jint objectIndex, jint volumeIndex) {
    if (objectIndex < 0 || volumeIndex < 0) return nullptr;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return nullptr;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return nullptr;
    if (static_cast<size_t>(volumeIndex) >= mo->volumes.size()) return nullptr;
    const auto* mv = mo->volumes[volumeIndex];
    if (mv == nullptr) return nullptr;

    jint packed[3];
    packed[0] = mv->config.has("extruder")
        ? static_cast<jint>(mv->config.opt_int("extruder"))
        : -1;
    packed[1] = mv->is_mm_painted() ? 1 : 0;
    packed[2] = mv->is_seam_painted() ? 1 : 0;

    jintArray out = env->NewIntArray(3);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, 3, packed);
    return out;
}

JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPaintStateCounts(
        JNIEnv* env, jobject, jint objectIndex, jint volumeIndex, jint kind) {
    if (objectIndex < 0 || volumeIndex < 0) return nullptr;
    if (kind != 0 && kind != 1) return nullptr;
    const auto& objs = sapil::g_model.objects;
    if (static_cast<size_t>(objectIndex) >= objs.size()) return nullptr;
    const auto* mo = objs[objectIndex];
    if (mo == nullptr) return nullptr;
    if (static_cast<size_t>(volumeIndex) >= mo->volumes.size()) return nullptr;
    const auto* mv = mo->volumes[volumeIndex];
    if (mv == nullptr) return nullptr;

    const Slic3r::FacetsAnnotation& facets =
        (kind == 0) ? mv->mmu_segmentation_facets : mv->supported_facets;
    std::map<int, int> counts = sapil::count_paint_states(*mv, facets);

    std::vector<jint> packed;
    packed.reserve(counts.size() * 2);
    for (const auto& kv : counts) {
        packed.push_back(static_cast<jint>(kv.first));
        packed.push_back(static_cast<jint>(kv.second));
    }

    jintArray out = env->NewIntArray(static_cast<jsize>(packed.size()));
    if (out == nullptr) return nullptr;
    if (!packed.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(packed.size()), packed.data());
    }
    return out;
}

} // extern "C"
