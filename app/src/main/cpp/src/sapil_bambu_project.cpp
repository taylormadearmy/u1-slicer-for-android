// sapil_bambu_project.cpp
//
// Phase 1 sub-plan #5: project-level config JNI accessor. Pure read of
// g_is_bbl, g_file_version, and getModelConfig()'s filament_colour /
// filament_settings_id / filament_ids strings. Returns the same JSON shape
// the Kotlin snapshot path will parse. Phase 0 made g_is_bbl and
// g_file_version externally linkable; getModelConfig() is the established
// public accessor pattern (see sapil_bambu_snapshot.cpp). Callers hold
// NativeLibrary.previewMutex on the Kotlin side; C++ assumes serialised access.

#include <jni.h>

#include <sstream>
#include <string>

#include "libslic3r/Config.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/Semver.hpp"

#include "sapil_bambu_snapshot.h"  // for sapil::json_escape, sapil::colour_to_hex

namespace sapil {
// Provided by sapil_model.cpp.
extern Slic3r::Model g_model;
extern bool g_is_bbl;
extern Slic3r::Semver g_file_version;
extern Slic3r::DynamicPrintConfig& getModelConfig();
} // namespace sapil

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetProjectConfig(JNIEnv* env, jobject) {
    // Match the sub-plan #1 "no model loaded → null" contract.
    if (sapil::g_model.objects.empty()) return nullptr;

    std::ostringstream out;
    out << "{";
    out << "\"isBbl\":" << (sapil::g_is_bbl ? "true" : "false") << ",";
    // Semver::valid() is false for default-constructed / invalid — emit "" to
    // match the Kotlin snapshot path's empty-string contract.
    out << "\"fileVersion\":\""
        << sapil::json_escape(sapil::g_file_version.valid() ? sapil::g_file_version.to_string() : "")
        << "\",";

    const auto& cfg = sapil::getModelConfig();
    const auto* colours = cfg.opt<Slic3r::ConfigOptionStrings>("filament_colour");
    const auto* settings_ids = cfg.opt<Slic3r::ConfigOptionStrings>("filament_settings_id");
    const auto* filament_ids = cfg.opt<Slic3r::ConfigOptionStrings>("filament_ids");

    out << "\"filamentColours\":[";
    if (colours != nullptr) {
        for (size_t i = 0; i < colours->values.size(); ++i) {
            if (i) out << ",";
            out << "\"" << sapil::json_escape(sapil::colour_to_hex(colours->values[i])) << "\"";
        }
    }
    out << "],";

    // Match append_plate's fallback order: prefer filament_settings_id,
    // else filament_ids. The Kotlin consumer reads this list verbatim.
    out << "\"filamentSettingsIds\":[";
    const Slic3r::ConfigOptionStrings* settings_fallback =
        settings_ids != nullptr ? settings_ids : filament_ids;
    if (settings_fallback != nullptr) {
        for (size_t i = 0; i < settings_fallback->values.size(); ++i) {
            if (i) out << ",";
            out << "\"" << sapil::json_escape(settings_fallback->values[i]) << "\"";
        }
    }
    out << "],";

    out << "\"filamentIds\":[";
    if (filament_ids != nullptr) {
        for (size_t i = 0; i < filament_ids->values.size(); ++i) {
            if (i) out << ",";
            out << "\"" << sapil::json_escape(filament_ids->values[i]) << "\"";
        }
    }
    out << "]";
    out << "}";

    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"
