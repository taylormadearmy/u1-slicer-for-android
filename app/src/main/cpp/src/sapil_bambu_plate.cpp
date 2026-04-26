// sapil_bambu_plate.cpp
//
// Phase 1 sub-plan #2: per-plate PlateData JNI accessors. Pure reads of
// g_plate_data_list and getModelConfig()'s project-level palette fallback
// (so append_plate's cascade matches Phase 0). Callers hold
// NativeLibrary.previewMutex on the Kotlin side; C++ assumes serialised
// access. Phase 0 made g_plate_data_list externally linkable in
// sapil_model.cpp.

#include <jni.h>

#include <sstream>
#include <string>

#include "libslic3r/Config.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"  // PlateDataPtrs
#include "libslic3r/Model.hpp"

#include "sapil_bambu_snapshot.h"  // sapil::append_plate

namespace sapil {
extern Slic3r::Model g_model;
extern Slic3r::PlateDataPtrs g_plate_data_list;
extern Slic3r::DynamicPrintConfig& getModelConfig();
} // namespace sapil

extern "C" {

JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPlateCount(JNIEnv*, jobject) {
    // Symmetric guard with nativeGetPlateData / the sub-plan #1 volume
    // accessors: "no model loaded" is reported as 0 regardless of whether
    // g_plate_data_list has transient residual state from a prior load.
    if (sapil::g_model.objects.empty()) return 0;
    return static_cast<jint>(sapil::g_plate_data_list.size());
}

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPlateData(
        JNIEnv* env, jobject, jint plateIndex) {
    if (plateIndex < 0) return nullptr;
    if (sapil::g_model.objects.empty()) return nullptr;
    const auto& plates = sapil::g_plate_data_list;
    // Positional index into the vector. PlateData::plate_index is also
    // 0-based inside g_plate_data_list after BBS importer normalisation
    // (bbs_3mf.cpp ~line 1485: plate->plate_index = raw-1); Phase 0's
    // bambu_snapshot_json loop over `g_plate_data_list[i]` uses the same
    // convention.
    if (static_cast<size_t>(plateIndex) >= plates.size()) return nullptr;
    const Slic3r::PlateData* p = plates[plateIndex];
    if (p == nullptr) return nullptr;

    const auto& cfg = sapil::getModelConfig();
    const auto* colours = cfg.opt<Slic3r::ConfigOptionStrings>("filament_colour");
    const auto* settings_ids = cfg.opt<Slic3r::ConfigOptionStrings>("filament_settings_id");
    const auto* filament_ids = cfg.opt<Slic3r::ConfigOptionStrings>("filament_ids");

    std::ostringstream out;
    sapil::append_plate(out, *p, colours, filament_ids, settings_ids);
    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"
