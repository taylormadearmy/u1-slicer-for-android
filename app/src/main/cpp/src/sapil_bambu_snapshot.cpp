// sapil_bambu_snapshot.cpp
//
// Phase 0 differential harness: walks the global Slic3r::Model after
// Model::read_from_file completes, emitting a JSON shaped exactly like
// the Kotlin BambuFileSnapshot. Compared against the Kotlin parser path
// to surface drift.
//
// Task 5: per-plate fields populated from PlateData. isBbl/fileVersion
// now reflect the actual out-params from Model::read_from_file.
// Task 6: per-object fields (objectId/name/extruder/sourcePath) populated
// from g_model.objects; bambu_snapshot_json refactored into a thin
// orchestrator over append_plate/append_object helpers.

#include "sapil_bambu_snapshot.h"

#include <cstdio>
#include <sstream>
#include <string>

#include <map>

#include "libslic3r/Model.hpp"
#include "libslic3r/Config.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/CustomGCode.hpp"
#include "libslic3r/ProjectTask.hpp"  // FilamentInfo
#include "libslic3r/Semver.hpp"
#include "libslic3r/TriangleSelector.hpp"  // EnforcerBlockerType

namespace sapil {

// Provided by sapil_model.cpp (non-static, file-scope externally linkable).
extern Slic3r::Model g_model;

// ModelInfo is defined in sapil.h within namespace sapil.
// g_model_info.filename is populated by SlicerEngine::loadModel.
extern ModelInfo g_model_info;

// Bambu-specific out-params captured by SlicerEngine::loadModel from
// Slic3r::Model::read_from_file. Reset at the top of each load.
extern Slic3r::PlateDataPtrs g_plate_data_list;
extern bool g_is_bbl;
extern Slic3r::Semver g_file_version;

// Accessor for the model-level embedded config (Metadata/project_settings.config).
// Defined in sapil_model.cpp, exposed here so non-sliced 3MFs (PlateData has
// empty slice_filaments_info) can still surface the effective per-plate palette.
extern Slic3r::DynamicPrintConfig& getModelConfig();

// Promoted to namespace sapil for reuse by sub-plan #5's sapil_bambu_project.cpp.
// Bodies unchanged — callers inside the anonymous namespace below still resolve
// via unqualified lookup (anon namespace in sapil → sapil::json_escape).
std::string json_escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"':  out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x",
                                  static_cast<unsigned int>(static_cast<unsigned char>(c)));
                    out += buf;
                } else {
                    out += c;
                }
                break;
        }
    }
    return out;
}

// PlateData stores filament colours as `#RRGGBB` already, but defensively
// prefix a missing `#` so the Kotlin decoder's `startsWith("#")` check holds.
std::string colour_to_hex(const std::string& raw) {
    if (raw.empty()) return "";
    if (raw[0] == '#') return raw;
    return "#" + raw;
}

// Promoted to namespace sapil for reuse by sub-plan #2's sapil_bambu_plate.cpp.
// Body unchanged.
//
// NOTE: Returns BambuStudio's canonical enum names (matching CustomGCode::Item::from_json's
// str2type map). The Kotlin snapshot path emits the raw XML attribute "1"/"2"/etc., so this
// will appear as a known disagreement in the Task 9 diff harness — documented in
// known-disagreements.json rather than fixed at source. Both representations are correct;
// pick one to normalise to in Phase 1 if it ever causes confusion.
//
// Maps CustomGCode::Type enum (libslic3r/CustomGCode.hpp) to the canonical
// names BambuStudio uses in its JSON serialisation — kept in sync with the
// str2type map in CustomGCode::Item::from_json.
const char* custom_gcode_type_name(int type) {
    using Slic3r::CustomGCode::Type;
    switch (static_cast<Type>(type)) {
        case Type::ColorChange: return "ColorChange";
        case Type::PausePrint:  return "PausePrint";
        case Type::ToolChange:  return "ToolChange";
        case Type::Template:    return "Template";
        case Type::Custom:      return "Custom";
        case Type::Unknown:     return "Unknown";
    }
    return "Unknown";
}

// Promoted to namespace sapil for reuse by sub-plan #2's sapil_bambu_plate.cpp.
// Body unchanged.
//
// Emit the JSON body for a single PlateSnapshot. Hoisted out of
// bambu_snapshot_json so the orchestrator stays scannable as Task 7 adds
// more sections. Project-level palette fallbacks are injected from the
// caller — they're invariant across plates, so looking them up once and
// threading them in keeps the inner loop honest about what varies.
void append_plate(std::ostringstream& out,
                  const Slic3r::PlateData& p,
                  const Slic3r::ConfigOptionStrings* project_colours,
                  const Slic3r::ConfigOptionStrings* project_filament_ids,
                  const Slic3r::ConfigOptionStrings* project_filament_settings_id) {
    out << "{";
    out << "\"plateIndex\":" << p.plate_index << ",";

    out << "\"filamentColours\":[";
    if (!p.slice_filaments_info.empty()) {
        for (size_t j = 0; j < p.slice_filaments_info.size(); ++j) {
            if (j) out << ",";
            out << "\"" << json_escape(colour_to_hex(p.slice_filaments_info[j].color)) << "\"";
        }
    } else if (project_colours != nullptr) {
        for (size_t j = 0; j < project_colours->values.size(); ++j) {
            if (j) out << ",";
            out << "\"" << json_escape(colour_to_hex(project_colours->values[j])) << "\"";
        }
    }
    out << "],";

    // filamentSettingsIds: prefer PlateData's per-filament `filament_id`
    // (tray profile id) when present, else fall back to the project-level
    // `filament_settings_id` array (user-preset names), else `filament_ids`.
    out << "\"filamentSettingsIds\":[";
    if (!p.slice_filaments_info.empty()) {
        for (size_t j = 0; j < p.slice_filaments_info.size(); ++j) {
            if (j) out << ",";
            out << "\"" << json_escape(p.slice_filaments_info[j].filament_id) << "\"";
        }
    } else {
        const Slic3r::ConfigOptionStrings* fallback =
            project_filament_settings_id != nullptr ? project_filament_settings_id : project_filament_ids;
        if (fallback != nullptr) {
            for (size_t j = 0; j < fallback->values.size(); ++j) {
                if (j) out << ",";
                out << "\"" << json_escape(fallback->values[j]) << "\"";
            }
        }
    }
    out << "],";

    out << "\"objectInstanceMap\":[";
    for (size_t j = 0; j < p.objects_and_instances.size(); ++j) {
        if (j) out << ",";
        out << "{\"objectId\":" << p.objects_and_instances[j].first
            << ",\"instanceId\":" << p.objects_and_instances[j].second << "}";
    }
    out << "],";

    // PlateData::plate_index is **normalised to 0-based** by the BBS
    // importer (see bbs_3mf.cpp ~line 1485: `plate->plate_index = raw-1`).
    // Model::plates_custom_gcodes is also keyed by 0-based plate index
    // (BambuStudio stores XML `<plate_info id="N">` at key `N-1`; see
    // bbs_3mf.cpp ~line 3099). So the lookup here is a direct match.
    out << "\"customGcode\":[";
    auto it = g_model.plates_custom_gcodes.find(p.plate_index);
    if (it != g_model.plates_custom_gcodes.end()) {
        const auto& items = it->second.gcodes;
        for (size_t j = 0; j < items.size(); ++j) {
            if (j) out << ",";
            const auto& g = items[j];
            // %.17g is the IEEE-754 round-trip-safe format for doubles.
            // ostringstream defaults to 6 significant digits, which would
            // round real layer Zs like 12.3456789 to "12.3457" and diverge
            // from Kotlin's Double.toString() representation in Task 9.
            char zbuf[32];
            std::snprintf(zbuf, sizeof(zbuf), "%.17g", g.print_z);
            out << "{"
                << "\"printZ\":" << zbuf << ","
                << "\"type\":\"" << json_escape(custom_gcode_type_name(static_cast<int>(g.type))) << "\","
                << "\"extruder\":" << g.extruder << ","
                << "\"color\":\"" << json_escape(g.color) << "\""
                << "}";
        }
    }
    out << "],";

    // plateConfig: stringify every key in p.config via opt_serialize so
    // the Kotlin decoder's getString() call succeeds on every entry
    // (see BambuFileSnapshot.kt PlateSnapshot.plateConfig KDoc).
    out << "\"plateConfig\":{";
    bool first_kv = true;
    for (const std::string& key : p.config.keys()) {
        if (!first_kv) out << ",";
        out << "\"" << json_escape(key) << "\":\""
            << json_escape(p.config.opt_serialize(key)) << "\"";
        first_kv = false;
    }
    out << "}";

    out << "}";
}

// Promoted to namespace sapil for reuse by sub-plan #4's sapil_bambu_objects.cpp.
// Body unchanged.
//
// Emit the JSON body for a single ObjectSnapshot. `extruder` follows the
// BambuFileSnapshot contract: 1-based with 0 meaning "unset / inherit"
// — which happens to match Slic3r's own representation (ModelConfigObject
// stores extruder as int with 0 = no override), so no translation is
// needed here. The diff harness expects objectId as Slic3r's internal
// runtime ObjectID (from ObjectBase::id().id, a size_t); the Kotlin
// parser path will populate this with a parity value in Task 9.
void append_object(std::ostringstream& out, const Slic3r::ModelObject& mo) {
    int extruder_value = 0;
    if (mo.config.has("extruder")) {
        extruder_value = mo.config.opt_int("extruder");
    }
    out << "{"
        << "\"objectId\":" << static_cast<long long>(mo.id().id) << ","
        << "\"name\":\"" << json_escape(mo.name) << "\","
        << "\"extruder\":" << extruder_value << ","
        << "\"sourcePath\":\"" << json_escape(mo.input_file) << "\""
        << "}";
}

namespace {

// Emit one VolumeSnapshot. `extruder` is nullable per the Task 1 contract:
// when the volume has no per-volume extruder override (config.has("extruder")
// is false) we emit JSON null rather than 0, distinguishing "inherit from
// object" from "explicitly set to 0" on the wire. Object-level extruder uses
// 0-as-inherit in ObjectSnapshot because that matches Slic3r's own
// representation (ModelConfigObject stores int with 0=unset); at the volume
// level we go with the tri-state the Kotlin data class already exposes.
void append_volume(std::ostringstream& out,
                   long long object_id,
                   size_t volume_index,
                   const Slic3r::ModelVolume& mv) {
    out << "{";
    out << "\"objectId\":" << object_id << ",";
    out << "\"volumeIndex\":" << volume_index << ",";

    if (mv.config.has("extruder")) {
        out << "\"extruder\":" << mv.config.opt_int("extruder") << ",";
    } else {
        out << "\"extruder\":null,";
    }

    auto mmu_counts = count_paint_states(mv, mv.mmu_segmentation_facets);
    out << "\"paintStateSet\":{";
    bool first = true;
    for (const auto& kv : mmu_counts) {
        if (!first) out << ",";
        out << "\"" << kv.first << "\":" << kv.second;
        first = false;
    }
    out << "},";

    auto sup_counts = count_paint_states(mv, mv.supported_facets);
    out << "\"paintSupportsStateSet\":{";
    first = true;
    for (const auto& kv : sup_counts) {
        if (!first) out << ",";
        out << "\"" << kv.first << "\":" << kv.second;
        first = false;
    }
    out << "},";

    out << "\"isMmPainted\":" << (mv.is_mm_painted() ? "true" : "false") << ",";
    out << "\"isSeamPainted\":" << (mv.is_seam_painted() ? "true" : "false");
    out << "}";
}

} // namespace

// Counts triangles per paint state for one FacetsAnnotation on a ModelVolume.
// Returns map<state_value, triangle_count> for every state with non-zero triangle
// count. State 0 (NONE) is never emitted — it is the unpainted default.
//
// EnforcerBlockerType is a scoped enum with the useful state range
// 1..ExtruderMax (currently 16). For mmu_segmentation, state N maps to the
// 1-based paint-slot index (Extruder1=1, Extruder2=2, ...). For supported_facets,
// state 1 = ENFORCER and state 2 = BLOCKER. We iterate the full range and let
// has_facets filter — the cost is 16 boolean calls per volume per annotation,
// negligible compared to the parse cost.
std::map<int, int> count_paint_states(const Slic3r::ModelVolume& mv,
                                      const Slic3r::FacetsAnnotation& facets) {
    // CAUTION (post-Buzz-plate-8 review):
    //   `has_facets(state)` uses direct equality (no H2C fold), but
    //   `get_facets(state)` applies the fold (state N+4 matches query in
    //   [1..4]). This function relies on that asymmetry: the `has_facets`
    //   guard returns false for query=2 when a volume's painted triangles
    //   are all in state 6 (Buzz plate 8 case), so the fold-contaminated
    //   `get_facets` is never called and `paintExtruders` correctly reports
    //   {6} only — not {2, 6}.
    //
    //   DO NOT change the `has_facets` call here to use `h2c_state_matches`.
    //   Doing so would surface phantom states (e.g. Buzz plate 8 would report
    //   state 2 alongside state 6) and regress chip counts in the UI.
    std::map<int, int> counts;
    const int max_state = static_cast<int>(Slic3r::EnforcerBlockerType::ExtruderMax);
    for (int state = 1; state <= max_state; ++state) {
        auto type = static_cast<Slic3r::EnforcerBlockerType>(state);
        if (facets.has_facets(mv, type)) {
            indexed_triangle_set its = facets.get_facets(mv, type);
            int n = static_cast<int>(its.indices.size());
            if (n > 0) counts[state] = n;
        }
    }
    return counts;
}

std::string bambu_snapshot_json() {
    if (g_model.objects.empty()) return "";

    std::ostringstream out;
    out << "{";
    out << "\"source\":\"" << json_escape(g_model_info.filename) << "\",";
    out << "\"isBbl\":" << (g_is_bbl ? "true" : "false") << ",";
    // Kotlin's snapshot path emits "" when the <metadata name="BambuStudio:3mfVersion">
    // entry is missing. Slic3r::Semver default-constructs to {0,0,0,0} whose
    // to_string() is "0.0.0.0" — if we passed that through unchanged the diff
    // harness would fire on every file without a fileVersion metadata entry.
    // Semver::valid() returns false for zero/inf/invalid, matching that empty case.
    out << "\"fileVersion\":\"" << json_escape(g_file_version.valid() ? g_file_version.to_string() : "") << "\",";

    // Project-level palette lookups are hoisted out of the plate loop — these
    // model-level config options (filament_colour / filament_ids /
    // filament_settings_id) do not change between plates. PlateData's own
    // slice_filaments_info is still consulted first inside append_plate; this
    // is only the fallback for non-sliced 3MFs where PlateData carries an
    // empty filaments list and the plate reuses the project palette.
    const auto& project_cfg = getModelConfig();
    const auto* project_colours = project_cfg.opt<Slic3r::ConfigOptionStrings>("filament_colour");
    const auto* project_filament_ids = project_cfg.opt<Slic3r::ConfigOptionStrings>("filament_ids");
    const auto* project_filament_settings_id =
        project_cfg.opt<Slic3r::ConfigOptionStrings>("filament_settings_id");

    out << "\"plates\":[";
    for (size_t i = 0; i < g_plate_data_list.size(); ++i) {
        if (i) out << ",";
        if (g_plate_data_list[i] == nullptr) { out << "null"; continue; }
        append_plate(out, *g_plate_data_list[i],
                     project_colours, project_filament_ids, project_filament_settings_id);
    }
    out << "],";

    out << "\"objects\":[";
    for (size_t i = 0; i < g_model.objects.size(); ++i) {
        if (i) out << ",";
        append_object(out, *g_model.objects[i]);
    }
    out << "],";

    out << "\"volumes\":[";
    bool first_vol = true;
    for (size_t oi = 0; oi < g_model.objects.size(); ++oi) {
        const Slic3r::ModelObject* mo = g_model.objects[oi];
        if (mo == nullptr) continue;
        long long obj_id = static_cast<long long>(mo->id().id);
        for (size_t vi = 0; vi < mo->volumes.size(); ++vi) {
            if (mo->volumes[vi] == nullptr) continue;
            if (!first_vol) out << ",";
            first_vol = false;
            append_volume(out, obj_id, vi, *mo->volumes[vi]);
        }
    }
    out << "]";
    out << "}";
    return out.str();
}

} // namespace sapil
