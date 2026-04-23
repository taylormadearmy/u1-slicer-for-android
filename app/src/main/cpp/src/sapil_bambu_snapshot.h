// sapil_bambu_snapshot.h — Internal header for Bambu snapshot implementation.
// Public declaration (bambu_snapshot_json) lives in ../include/sapil.h.
// This header exists so sapil_bambu_snapshot.cpp can include the public
// surface without implying any further dependencies to other TUs.
#pragma once

#include "../include/sapil.h"
#include <map>

namespace Slic3r {
    class ModelVolume;
    class FacetsAnnotation;
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

} // namespace sapil
