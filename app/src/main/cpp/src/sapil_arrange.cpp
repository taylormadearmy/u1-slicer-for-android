#include "../include/sapil.h"
#include "sapil_internal.h"
#include "libslic3r/Model.hpp"
#include <cmath>
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/TriangleMesh.hpp"

// =============================================================================
// sapil_arrange.cpp — Multiple copy instance placement
// =============================================================================

namespace sapil {

// Forward declarations from sapil_model.cpp
extern Slic3r::Model& getGlobalModel();
extern bool isModelLoaded();
extern void invalidatePreviewMeshCache();
extern std::vector<Slic3r::Vec3d>& getRotationBasePositions();
extern std::vector<Slic3r::Vec3d>& getRotationBaseRotations();

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
        // Offset is set so the mesh lower-left corner lands at the target position.
        // Since world_min = scale * meshBB.min + offset, we need:
        //   offset = pos - scale * meshBB.min
        // This is correct for all mesh origins (including Bambu 3MF where mesh
        // vertices are at arbitrary world positions, not necessarily at 0,0).
        for (auto* obj : model.objects) {
            if (obj->instances.empty()) continue;

            // Mesh-space bounding box: union of all model volumes' meshes after each
            // volume's local transform (`v->get_matrix()`) is applied. Multi-volume
            // objects (dual-colour merged calicube, compound Bambu objects) place
            // their volumes at distinct positions within the object via per-volume
            // transforms, so a raw `vol->mesh().bounding_box()` union under-reports
            // the true mesh-space AABB and the lower-left convention math below
            // ends up off by the difference.
            //
            // Slic3r's `ModelObject::raw_mesh_bounding_box()` does the right merge
            // (`v->mesh().transformed_bounding_box(v->get_matrix())`) and is what
            // the slicer itself uses; reusing it here keeps the offset math in
            // lockstep with where the slicer actually places the geometry.
            const Slic3r::BoundingBoxf3& meshBB = obj->raw_mesh_bounding_box();

            // Save the transformation before clearing.
            // Also read the per-axis scale so the mesh-space min can be scaled correctly.
            // Offset must be: pos - scale * meshBB.min
            // (not pos - meshBB.min, which ignores the instance scale and misplaces the
            //  model for any non-unity scale where meshBB.min ≠ 0).
            auto trafo = obj->instances[0]->get_transformation();
            const Slic3r::Vec3d sf = trafo.get_scaling_factor();
            obj->clear_instances();

            for (const auto& pos : positions) {
                auto* inst = obj->add_instance();
                inst->set_transformation(trafo);
                // Place mesh lower-left corner at pos:
                //   world_min = scale * meshBB.min + offset  →  offset = pos - scale * meshBB.min
                inst->set_offset(Slic3r::Vec3d(
                    static_cast<double>(pos.first)  - sf.x() * meshBB.min.x(),
                    static_cast<double>(pos.second) - sf.y() * meshBB.min.y(),
                    -sf.z() * meshBB.min.z()
                ));
            }
        }
    }

    invalidatePreviewMeshCache();
    SAPIL_LOGI("Set %d instance(s) across %d object(s)",
        (int)positions.size(), (int)model.objects.size());
    return true;
}

bool SlicerEngine::setModelScale(float x, float y, float z) {
    if (!isModelLoaded()) {
        SAPIL_LOGE("setModelScale: no model loaded");
        return false;
    }
    Slic3r::Model& model = getGlobalModel();

    // For multi-object models, scale around the combined center so gaps between
    // objects scale proportionally (e.g. calicube cubes stay evenly spaced).
    // 1. Compute the combined bounding box center of all instances.
    Slic3r::BoundingBoxf3 worldBB;
    for (auto* obj : model.objects) {
        for (auto* inst : obj->instances) {
            worldBB.merge(inst->transform_bounding_box(obj->raw_bounding_box()));
        }
    }
    const Slic3r::Vec3d center = worldBB.center();

    // 2. Scale each instance's mesh AND adjust its position relative to center.
    for (auto* obj : model.objects) {
        for (auto* inst : obj->instances) {
            // Current world position of this instance's object origin
            const Slic3r::Vec3d pos = inst->get_offset();
            // Scale the offset from the group center
            inst->set_offset(Slic3r::Vec3d(
                center.x() + (pos.x() - center.x()) * static_cast<double>(x),
                center.y() + (pos.y() - center.y()) * static_cast<double>(y),
                center.z() + (pos.z() - center.z()) * static_cast<double>(z)
            ));
            inst->set_scaling_factor(Slic3r::Vec3d(
                static_cast<double>(x),
                static_cast<double>(y),
                static_cast<double>(z)
            ));
        }
    }
    invalidatePreviewMeshCache();
    SAPIL_LOGI("Set model scale: %.3f, %.3f, %.3f (center: %.1f, %.1f, %.1f)",
        x, y, z, center.x(), center.y(), center.z());
    return true;
}

// Last user-requested rotation (degrees). Used to skip redundant calls
// (e.g. tab switch re-triggers LaunchedEffect with same rotation value).
static float g_last_rx_deg = 0.f, g_last_ry_deg = 0.f, g_last_rz_deg = 0.f;
static bool g_last_rotation_set = false;

void resetLastRotation() {
    g_last_rotation_set = false;
    g_last_rx_deg = g_last_ry_deg = g_last_rz_deg = 0.f;
}

bool SlicerEngine::setModelRotation(float rx_deg, float ry_deg, float rz_deg) {
    if (!isModelLoaded()) {
        SAPIL_LOGE("setModelRotation: no model loaded");
        return false;
    }

    // Skip if rotation hasn't changed — avoids invalidating the preview mesh cache
    // on tab switch where the composable re-fires with the same rotation value.
    if (g_last_rotation_set &&
        rx_deg == g_last_rx_deg && ry_deg == g_last_ry_deg && rz_deg == g_last_rz_deg) {
        SAPIL_LOGI("setModelRotation: unchanged (%.1f, %.1f, %.1f), skipping", rx_deg, ry_deg, rz_deg);
        return true;
    }
    g_last_rx_deg = rx_deg;
    g_last_ry_deg = ry_deg;
    g_last_rz_deg = rz_deg;
    g_last_rotation_set = true;

    Slic3r::Model& model = getGlobalModel();
    std::vector<Slic3r::Vec3d>& basePositions = getRotationBasePositions();
    std::vector<Slic3r::Vec3d>& baseRotations = getRotationBaseRotations();

    // Flatten all instances into a list (same order every call: object then instance)
    std::vector<Slic3r::ModelInstance*> allInsts;
    for (auto* obj : model.objects) {
        for (auto* inst : obj->instances) {
            allInsts.push_back(inst);
        }
    }

    // On first call after model load, snapshot current offsets and rotations as base.
    // The base rotations preserve embedded 3MF rotations (e.g. 90° Z from build item).
    if (basePositions.empty()) {
        for (auto* inst : allInsts) {
            basePositions.push_back(inst->get_offset());
            baseRotations.push_back(inst->get_rotation());
        }
    }

    const double deg2rad = M_PI / 180.0;
    const double rx = static_cast<double>(rx_deg) * deg2rad;
    const double ry = static_cast<double>(ry_deg) * deg2rad;
    const double rz = static_cast<double>(rz_deg) * deg2rad;

    // Compute group pivot: XY centre of the base-position bounding box.
    // Z pivot = 0 (rotate around the bed plane, not the mesh centroid).
    double minX = std::numeric_limits<double>::max();
    double minY = std::numeric_limits<double>::max();
    double maxX = std::numeric_limits<double>::lowest();
    double maxY = std::numeric_limits<double>::lowest();
    for (const auto& b : basePositions) {
        minX = std::min(minX, b.x()); maxX = std::max(maxX, b.x());
        minY = std::min(minY, b.y()); maxY = std::max(maxY, b.y());
    }
    const Slic3r::Vec3d pivot(
        (minX + maxX) * 0.5,
        (minY + maxY) * 0.5,
        0.0
    );

    // Build ZYX rotation matrix (OrcaSlicer convention: Z applied last → first in matrix terms).
    // R = Rz * Ry * Rx  (applied to column vectors)
    const double cx = std::cos(rx), sx = std::sin(rx);
    const double cy = std::cos(ry), sy = std::sin(ry);
    const double cz = std::cos(rz), sz = std::sin(rz);

    // Row-major 3x3: R[row][col]
    double R[3][3];
    R[0][0] = cy * cz;
    R[0][1] = cz * sx * sy - cx * sz;
    R[0][2] = cx * cz * sy + sx * sz;
    R[1][0] = cy * sz;
    R[1][1] = cx * cz + sx * sy * sz;
    R[1][2] = cx * sy * sz - cz * sx;
    R[2][0] = -sy;
    R[2][1] = cy * sx;
    R[2][2] = cx * cy;

    // Apply: new_offset = pivot + R * (base - pivot)
    for (size_t i = 0; i < allInsts.size(); ++i) {
        const Slic3r::Vec3d b = basePositions[i] - pivot;
        const Slic3r::Vec3d rotated(
            R[0][0] * b.x() + R[0][1] * b.y() + R[0][2] * b.z(),
            R[1][0] * b.x() + R[1][1] * b.y() + R[1][2] * b.z(),
            R[2][0] * b.x() + R[2][1] * b.y() + R[2][2] * b.z()
        );
        // Compose user rotation on top of the base (embedded) rotation so that
        // 3MF build-item rotations are preserved when user rotation is zero.
        // Note: additive Euler angles are only correct for single-axis rotation
        // (which is all the UI exposes — a Z-axis slider). Multi-axis composition
        // would need rotation matrix multiplication or quaternions.
        const Slic3r::Vec3d& base_rot = (i < baseRotations.size())
            ? baseRotations[i] : Slic3r::Vec3d::Zero();
        allInsts[i]->set_rotation(Slic3r::Vec3d(
            base_rot.x() + rx,
            base_rot.y() + ry,
            base_rot.z() + rz
        ));
        allInsts[i]->set_offset(pivot + rotated);
    }

    invalidatePreviewMeshCache();
    SAPIL_LOGI("Set model rotation: %.1f, %.1f, %.1f deg (pivot: %.1f, %.1f)",
        rx_deg, ry_deg, rz_deg, pivot.x(), pivot.y());
    return true;
}

std::vector<float> SlicerEngine::getInstanceOffsets() const {
    std::vector<float> result;
    if (!isModelLoaded()) return result;
    const Slic3r::Model& model = getGlobalModel();
    for (const auto* obj : model.objects) {
        for (const auto* inst : obj->instances) {
            const Slic3r::Vec3d off = inst->get_offset();
            result.push_back(static_cast<float>(off.x()));
            result.push_back(static_cast<float>(off.y()));
        }
    }
    return result;
}

} // namespace sapil
