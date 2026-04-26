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
        // Compute current world bounding box across all objects (mesh + instance
        // transform). The previous formulation called `obj->bounding_box_exact()`
        // which is cached behind `m_bounding_box_exact_valid`, and Slic3r does
        // NOT invalidate that cache when an instance's scaling factor changes
        // via `set_scaling_factor` (our setModelScale path). On a second
        // `setModelInstances` after `setModelScale`, the cached unit-scale BB
        // was used and the multi-object combined origin landed ~half-mesh-size
        // off (Review 1 nit, same root cause as the offset bug fixed in ea420ea
        // for the single-object branch).
        //
        // Inline-compute the world AABB by transforming each volume's mesh
        // through (instance_full_matrix * volume_matrix) and unioning. This
        // bypasses both `raw_bounding_box()` and `bounding_box_exact()`
        // staleness; same shape as the lines 88-97 fix on the single-object
        // branch.
        Slic3r::BoundingBoxf3 worldBB;
        for (auto* obj : model.objects) {
            for (auto* inst : obj->instances) {
                const Slic3r::Transform3d inst_full =
                    inst->get_transformation().get_matrix();
                for (const auto* v : obj->volumes) {
                    if (v->is_model_part()) {
                        worldBB.merge(
                            v->mesh().transformed_bounding_box(inst_full * v->get_matrix())
                        );
                    }
                }
            }
        }
        if (!worldBB.defined) {
            SAPIL_LOGE("setModelInstances multi-object: no model parts found");
            return false;
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

            // Compute the AABB of the object's volumes after applying both per-
            // volume local transforms AND the current instance's scale+rotation.
            // This matches `ModelObject::raw_bounding_box()` semantically but
            // bypasses its `m_raw_bounding_box_valid` cache, which Slic3r does
            // not invalidate when an instance's scaling factor changes via
            // `set_scaling_factor` (our setModelScale path). Without this, a
            // stale unit-scale bbox is used for the offset math after
            // setModelScale and the model lands ~half-mesh-size off.
            //
            // Why include instance no-offset transform here: for files whose
            // mesh is stored at non-canonical orientation (hanging file with a
            // baked rotation in instance trafo), the post-rotation AABB differs
            // from the mesh-space AABB. Using the instance-aware bbox keeps
            // setModelInstances in lockstep with how the slicer renders the
            // instance.
            const Slic3r::Transform3d inst_no_offset =
                obj->instances.front()->get_transformation().get_matrix_no_offset();
            Slic3r::BoundingBoxf3 effectiveBB;
            for (const auto* v : obj->volumes) {
                if (v->is_model_part()) {
                    effectiveBB.merge(
                        v->mesh().transformed_bounding_box(inst_no_offset * v->get_matrix())
                    );
                }
            }
            if (!effectiveBB.defined) continue;

            auto trafo = obj->instances[0]->get_transformation();
            obj->clear_instances();

            for (const auto& pos : positions) {
                auto* inst = obj->add_instance();
                inst->set_transformation(trafo);
                // Lower-left convention: world_min = inst.offset + effectiveBB.min,
                // so setting offset = pos - effectiveBB.min places the mesh
                // lower-left at pos. effectiveBB already incorporates the
                // instance scale; no separate sf multiplication needed.
                inst->set_offset(Slic3r::Vec3d(
                    static_cast<double>(pos.first)  - effectiveBB.min.x(),
                    static_cast<double>(pos.second) - effectiveBB.min.y(),
                    -effectiveBB.min.z()
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
    //
    // The previous formulation called `obj->raw_bounding_box()` (cached
    // behind `m_raw_bounding_box_valid`) and re-applied the full instance
    // matrix on top via `transform_bounding_box`. On a second `setModelScale`
    // call this double-applied scale: the cached unit-scale `raw_bounding_box`
    // already reflected the prior scale via per-volume matrices in some code
    // paths, and applying the new instance trafo on top placed the group
    // centre off by `(currentScale - 1) * meshHalfSize`. Same cache-staleness
    // shape as the offset bug fixed in ea420ea for `setModelInstances`
    // (Review 1 nit).
    //
    // Inline-compute the world AABB across all (instance × volume) by
    // applying `inst_full * volume_matrix` to each volume's mesh and
    // unioning. Bypasses both `raw_bounding_box()` and
    // `bounding_box_exact()` caches.
    Slic3r::BoundingBoxf3 worldBB;
    for (auto* obj : model.objects) {
        for (auto* inst : obj->instances) {
            const Slic3r::Transform3d inst_full =
                inst->get_transformation().get_matrix();
            for (const auto* v : obj->volumes) {
                if (v->is_model_part()) {
                    worldBB.merge(
                        v->mesh().transformed_bounding_box(inst_full * v->get_matrix())
                    );
                }
            }
        }
    }
    if (!worldBB.defined) {
        SAPIL_LOGE("setModelScale: no model parts found");
        return false;
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
