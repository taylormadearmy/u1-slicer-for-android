#include "../include/sapil.h"
#include "sapil_internal.h"
#include "libslic3r/Model.hpp"
#include "libslic3r/BoundingBox.hpp"

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
        // Compute current center from all objects' first instance offsets
        Slic3r::BoundingBoxf3 bb;
        for (auto* obj : model.objects) {
            if (!obj->instances.empty()) {
                bb.merge(obj->instances[0]->get_offset());
            }
        }
        Slic3r::Vec3d origCenter = bb.center();
        Slic3r::Vec3d delta(
            positions[0].first - origCenter.x(),
            positions[0].second - origCenter.y(),
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
        for (auto* obj : model.objects) {
            if (obj->instances.empty()) continue;

            // Save the transformation before clearing
            auto trafo = obj->instances[0]->get_transformation();
            obj->clear_instances();

            for (const auto& pos : positions) {
                auto* inst = obj->add_instance(trafo);
                inst->set_offset(Slic3r::Vec3d(
                    static_cast<double>(pos.first),
                    static_cast<double>(pos.second),
                    0.0
                ));
            }
        }
    }

    SAPIL_LOGI("Set %d instance(s) across %d object(s)",
        (int)positions.size(), (int)model.objects.size());
    return true;
}

} // namespace sapil
