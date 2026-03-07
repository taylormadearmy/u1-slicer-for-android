#include "../include/sapil.h"
#include "sapil_internal.h"
#include "libslic3r/Model.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/TriangleMesh.hpp"

// =============================================================================
// sapil_arrange.cpp — Multiple copy instance placement
// =============================================================================

namespace sapil {

// Forward declarations from sapil_model.cpp
extern Slic3r::Model& getGlobalModel();
extern bool isModelLoaded();

bool SlicerEngine::setModelInstances(const std::vector<std::pair<float, float>>& positions) {
    if (!isModelLoaded()) {
        SAPIL_LOGE("setModelInstances: no model loaded");
        return false;
    }
    if (positions.empty()) {
        SAPIL_LOGE("setModelInstances: no positions provided");
        return false;
    }

    Slic3r::Model& model = getGlobalModel();

    // Multi-object models (e.g. multi-color 3MF): move all objects by the
    // same delta to preserve their relative positions.
    bool multiObject = model.objects.size() > 1 && positions.size() == 1;

    if (multiObject) {
        // Compute current world bounding box across all objects (mesh + instance transform).
        // bounding_box_exact() returns world-space BB, so delta = pos - world_min
        // places the combined lower-left corner at the target position.
        Slic3r::BoundingBoxf3 worldBB;
        for (auto* obj : model.objects) {
            worldBB.merge(obj->bounding_box_exact());
        }
        Slic3r::Vec3d worldMin = worldBB.min;
        Slic3r::Vec3d delta(
            positions[0].first - worldMin.x(),
            positions[0].second - worldMin.y(),
            0.0
        );

        for (auto* obj : model.objects) {
            for (auto* inst : obj->instances) {
                auto offset = inst->get_offset();
                inst->set_offset(Slic3r::Vec3d(
                    offset.x() + delta.x(),
                    offset.y() + delta.y(),
                    offset.z()
                ));
            }
        }
    } else {
        // Single object, possibly multiple copies: preserve first instance's
        // transformation (rotation, scale, mirror) and clone it per position.
        // Offset is set so the mesh lower-left corner lands at the target position:
        //   offset = pos - meshBB.min
        // This is correct for all mesh origins (including Bambu 3MF where mesh
        // vertices are at arbitrary world positions, not necessarily at 0,0).
        for (auto* obj : model.objects) {
            if (obj->instances.empty()) continue;

            // Compute mesh-space bounding box (union of all volumes, before instance transform)
            Slic3r::BoundingBoxf3 meshBB;
            for (const auto* vol : obj->volumes) {
                meshBB.merge(vol->mesh().bounding_box());
            }

            // Save the transformation before clearing
            auto trafo = obj->instances[0]->get_transformation();
            obj->clear_instances();

            for (const auto& pos : positions) {
                auto* inst = obj->add_instance();
                inst->set_transformation(trafo);
                // Place mesh lower-left at pos; lift to z=0
                inst->set_offset(Slic3r::Vec3d(
                    static_cast<double>(pos.first)  - meshBB.min.x(),
                    static_cast<double>(pos.second) - meshBB.min.y(),
                    -meshBB.min.z()
                ));
            }
        }
    }

    SAPIL_LOGI("Set %d instance(s) across %d object(s)",
        (int)positions.size(), (int)model.objects.size());
    return true;
}

} // namespace sapil
