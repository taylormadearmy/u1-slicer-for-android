// sapil_bambu_snapshot.h — Internal header for Bambu snapshot implementation.
// Public declaration (bambu_snapshot_json) lives in ../include/sapil.h.
// This header exists so sapil_bambu_snapshot.cpp can include the public
// surface without implying any further dependencies to other TUs.
#pragma once

#include "../include/sapil.h"
#include <map>
#include <sstream>
#include <string>

namespace Slic3r {
    class ModelVolume;
    class FacetsAnnotation;
    class ConfigOptionStrings;
    struct PlateData;
    class ModelObject;
}

namespace sapil {

/**
 * Counts triangles per paint state on one FacetsAnnotation.
 * State 0 (NONE) is never emitted; returned map is sorted by state ascending.
 * Shared with Phase 1 sub-plan #1 JNI accessors — must stay behaviourally
 * identical to what `bambu_snapshot_json` emits for paint counts.
 */
std::map<int, int> count_paint_states(const Slic3r::ModelVolume& mv,
                                      const Slic3r::FacetsAnnotation& facets);

/**
 * JSON-escape a UTF-8 string for inclusion in a JSON string literal.
 * Shared with Phase 1 sub-plan #5 JNI accessor (sapil_bambu_project.cpp).
 */
std::string json_escape(const std::string& s);

/**
 * Defensively prefix a `#` when a stored filament colour lacks one, so the
 * Kotlin decoder's `startsWith("#")` check holds on every hex we emit.
 * Empty input returns empty. Shared with sub-plan #5.
 */
std::string colour_to_hex(const std::string& raw);

/**
 * Emit the Phase 0 per-plate JSON body (braces included) for one PlateData.
 * Project-level palette pointers are injected so the `filamentColours` and
 * `filamentSettingsIds` fallbacks cascade exactly as in bambu_snapshot_json.
 *
 * Shared with Phase 1 sub-plan #2 JNI accessor (sapil_bambu_plate.cpp).
 */
void append_plate(std::ostringstream& out,
                  const Slic3r::PlateData& p,
                  const Slic3r::ConfigOptionStrings* project_colours,
                  const Slic3r::ConfigOptionStrings* project_filament_ids,
                  const Slic3r::ConfigOptionStrings* project_filament_settings_id);

/**
 * Emit the Phase 0 per-object JSON body (braces included) for one ModelObject.
 * Matches the BambuFileSnapshot contract: objectId = runtime ObjectID (size_t),
 * extruder 0 = inherit/unset, sourcePath = input_file (component refs).
 *
 * Shared with Phase 1 sub-plan #4 JNI accessor (sapil_bambu_objects.cpp).
 */
void append_object(std::ostringstream& out, const Slic3r::ModelObject& mo);

} // namespace sapil
