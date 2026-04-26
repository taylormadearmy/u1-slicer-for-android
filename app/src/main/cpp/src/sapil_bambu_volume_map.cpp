// sapil_bambu_volume_map.cpp
//
// Phase 1 Task 2: JNI accessor returning per-object, per-volume extruder +
// paint data in a single JSON call. Replaces chatty per-volume queries and
// Kotlin's fragile XML-based objectPartExtruders synthesis.
// Callers hold NativeLibrary.previewMutex on the Kotlin side; C++ assumes
// serialised access.

#include <jni.h>

#include <sstream>

#include "libslic3r/Model.hpp"

#include "sapil_bambu_snapshot.h"

namespace sapil {
extern Slic3r::Model g_model;
} // namespace sapil

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetAllVolumeExtruders(
        JNIEnv* env, jobject) {
    if (sapil::g_model.objects.empty()) return nullptr;

    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < sapil::g_model.objects.size(); ++i) {
        if (i) out << ",";
        const auto* mo = sapil::g_model.objects[i];
        if (!mo) { out << "null"; continue; }

        int obj_ext = mo->config.has("extruder")
            ? mo->config.opt_int("extruder") : 0;

        out << "{\"objectIndex\":" << i
            << ",\"objectExtruder\":" << obj_ext
            << ",\"volumes\":[";

        for (size_t j = 0; j < mo->volumes.size(); ++j) {
            if (j) out << ",";
            const auto* mv = mo->volumes[j];
            if (!mv) { out << "null"; continue; }

            int vol_ext = mv->config.has("extruder")
                ? mv->config.opt_int("extruder") : -1;

            out << "{\"volumeIndex\":" << j
                << ",\"extruder\":" << vol_ext
                << ",\"isMmPainted\":" << (mv->is_mm_painted() ? "true" : "false")
                << ",\"isSeamPainted\":" << (mv->is_seam_painted() ? "true" : "false")
                << "}";
        }
        out << "]}";
    }
    out << "]";
    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"
