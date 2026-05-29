#include "../include/sapil.h"
#include "sapil_internal.h"
#include "libslic3r/Model.hpp"
#include "libslic3r/Orient.hpp"
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

// Per-instance scaling factors snapshotted the first time setModelScale() is called
// after a model load. See setModelScale() and resetLoadTimeScaleFactors() for details.
static std::vector<Slic3r::Vec3d> g_loadtime_scale_factors;

void resetLoadTimeScaleFactors() {
    g_loadtime_scale_factors.clear();
}

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
            obj->invalidate_bounding_box();
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

    // Snapshot the per-instance scaling factors on first call after a model load.
    // This preserves the file's embedded scale (e.g. 5.083× in BambuStudio exports where
    // geometry is stored at reduced scale with a large build-item transform). Subsequent
    // calls apply the user scale multiplicatively from this base, so user scale=0.6 means
    // 60% of the natural world size rather than 60% of the raw mesh-vertex coordinates.
    // B108: without this snapshot, setModelScale(0.6) overwrote the embedded 5.083× with
    // 0.6, producing a model ≈5× too small and floating off the bed.
    if (g_loadtime_scale_factors.empty()) {
        for (auto* obj : model.objects) {
            for (auto* inst : obj->instances) {
                g_loadtime_scale_factors.push_back(inst->get_scaling_factor());
            }
        }
    }

    // Inline-compute the world AABB across all (instance × volume).
    // Bypasses both raw_bounding_box() and bounding_box_exact() caches, which
    // Slic3r does not invalidate when set_scaling_factor() is called.
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

    // Apply scale to each instance.
    // XY offset: scale around the group XY center so multi-copy gaps stay proportional.
    // Effective sf = loadtime_sf × user_scale (multiplicative from file's natural scale).
    size_t sfIdx = 0;
    for (auto* obj : model.objects) {
        for (auto* inst : obj->instances) {
            const Slic3r::Vec3d pos = inst->get_offset();
            inst->set_offset(Slic3r::Vec3d(
                center.x() + (pos.x() - center.x()) * static_cast<double>(x),
                center.y() + (pos.y() - center.y()) * static_cast<double>(y),
                pos.z()   // Z will be corrected by the bed-snap pass below
            ));
            const Slic3r::Vec3d base_sf = (sfIdx < g_loadtime_scale_factors.size())
                ? g_loadtime_scale_factors[sfIdx]
                : Slic3r::Vec3d(1.0, 1.0, 1.0);
            inst->set_scaling_factor(Slic3r::Vec3d(
                base_sf.x() * static_cast<double>(x),
                base_sf.y() * static_cast<double>(y),
                base_sf.z() * static_cast<double>(z)
            ));
            ++sfIdx;
        }
    }

    // Bed-snap: for each instance, recompute its own world AABB after the scale change
    // and shift that instance's Z offset so its bottom lands at z=0. A single group-wide
    // shift only snaps the lowest object to the bed — objects with larger embedded Z
    // translations (e.g. skywing-seawing-silkwing: 2.89mm vs 16.75mm) would otherwise
    // remain floating above the bed and trigger "empty initial layer" slice errors
    // (B108 multi-object follow-up).
    for (auto* obj : model.objects) {
        for (auto* inst : obj->instances) {
            const Slic3r::Transform3d inst_full =
                inst->get_transformation().get_matrix();
            Slic3r::BoundingBoxf3 instBB;
            for (const auto* v : obj->volumes) {
                if (v->is_model_part()) {
                    instBB.merge(
                        v->mesh().transformed_bounding_box(inst_full * v->get_matrix())
                    );
                }
            }
            if (instBB.defined && instBB.min.z() != 0.0) {
                const Slic3r::Vec3d off = inst->get_offset();
                inst->set_offset(Slic3r::Vec3d(off.x(), off.y(), off.z() - instBB.min.z()));
            }
        }
    }

    for (auto* obj : model.objects) obj->invalidate_bounding_box();
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

    for (auto* obj : model.objects) obj->invalidate_bounding_box();
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

std::vector<float> SlicerEngine::getInstanceWorldZMins() const {
    std::vector<float> result;
    if (!isModelLoaded()) return result;
    const Slic3r::Model& model = getGlobalModel();
    for (const auto* obj : model.objects) {
        for (const auto* inst : obj->instances) {
            const Slic3r::Transform3d inst_full =
                inst->get_transformation().get_matrix();
            Slic3r::BoundingBoxf3 instBB;
            for (const auto* v : obj->volumes) {
                if (v->is_model_part()) {
                    instBB.merge(
                        v->mesh().transformed_bounding_box(inst_full * v->get_matrix())
                    );
                }
            }
            result.push_back(instBB.defined ? static_cast<float>(instBB.min.z()) : 0.0f);
        }
    }
    return result;
}

std::vector<float> SlicerEngine::getObjectWorldAABBMins() const {
    std::vector<float> result;
    if (!isModelLoaded()) return result;
    const Slic3r::Model& model = getGlobalModel();
    // One (minX, minY) pair per object — uses instance[0] only (the renderer
    // and placement layer work in instance[0] space).
    for (const auto* obj : model.objects) {
        if (obj->instances.empty()) {
            result.push_back(0.f);
            result.push_back(0.f);
            continue;
        }
        const Slic3r::Transform3d inst_full =
            obj->instances[0]->get_transformation().get_matrix();
        Slic3r::BoundingBoxf3 instBB;
        for (const auto* v : obj->volumes) {
            if (v->is_model_part()) {
                instBB.merge(
                    v->mesh().transformed_bounding_box(inst_full * v->get_matrix())
                );
            }
        }
        if (instBB.defined) {
            result.push_back(static_cast<float>(instBB.min.x()));
            result.push_back(static_cast<float>(instBB.min.y()));
        } else {
            result.push_back(0.f);
            result.push_back(0.f);
        }
    }
    return result;
}

std::vector<float> SlicerEngine::getObjectBoundingBoxes() const {
    std::vector<float> result;
    if (!isModelLoaded()) return result;
    const Slic3r::Model& model = getGlobalModel();
    for (const auto* obj : model.objects) {
        if (obj->instances.empty()) {
            result.push_back(0); result.push_back(0); result.push_back(0);
            continue;
        }
        const Slic3r::Transform3d inst_no_offset =
            obj->instances[0]->get_transformation().get_matrix_no_offset();
        Slic3r::BoundingBoxf3 bb;
        for (const auto* v : obj->volumes) {
            if (v->is_model_part()) {
                bb.merge(v->mesh().transformed_bounding_box(inst_no_offset * v->get_matrix()));
            }
        }
        if (bb.defined) {
            result.push_back(static_cast<float>(bb.max.x() - bb.min.x()));
            result.push_back(static_cast<float>(bb.max.y() - bb.min.y()));
            result.push_back(static_cast<float>(bb.max.z() - bb.min.z()));
        } else {
            result.push_back(0); result.push_back(0); result.push_back(0);
        }
    }
    return result;
}

bool SlicerEngine::setObjectPositions(const std::vector<std::pair<float, float>>& positions) {
    if (!isModelLoaded()) {
        SAPIL_LOGE("setObjectPositions: no model loaded");
        return false;
    }
    Slic3r::Model& model = getGlobalModel();
    if (positions.size() != model.objects.size()) {
        SAPIL_LOGE("setObjectPositions: positions count %d != object count %d",
            (int)positions.size(), (int)model.objects.size());
        return false;
    }
    for (size_t i = 0; i < model.objects.size(); ++i) {
        auto* obj = model.objects[i];
        if (obj->instances.empty()) continue;
        const Slic3r::Transform3d inst_no_offset =
            obj->instances[0]->get_transformation().get_matrix_no_offset();
        Slic3r::BoundingBoxf3 effectiveBB;
        for (const auto* v : obj->volumes) {
            if (v->is_model_part()) {
                effectiveBB.merge(
                    v->mesh().transformed_bounding_box(inst_no_offset * v->get_matrix()));
            }
        }
        if (!effectiveBB.defined) continue;
        obj->instances[0]->set_offset(Slic3r::Vec3d(
            static_cast<double>(positions[i].first)  - effectiveBB.min.x(),
            static_cast<double>(positions[i].second) - effectiveBB.min.y(),
            -effectiveBB.min.z()
        ));
        // ModelInstance::set_offset() does not invalidate the parent ModelObject's
        // bounding-box cache (m_bounding_box_exact_valid). Without this, the auto-center
        // guard in slice() calls bounding_box_exact() and gets a stale value from before
        // the first setObjectPositions call (e.g. the benchy's default offset=0 after
        // loadModel), thinks the model is off-bed, and applies a spurious auto-center
        // delta that corrupts all object positions.
        obj->invalidate_bounding_box();
    }
    invalidatePreviewMeshCache();
    SAPIL_LOGI("setObjectPositions: positioned %d objects", (int)positions.size());
    return true;
}

// =============================================================================
// F66 — Split + Auto-Orient + per-object pose
// =============================================================================

static bool f66_objIdxValid(int objIdx) {
    if (!isModelLoaded()) return false;
    const auto& objs = getGlobalModel().objects;
    return objIdx >= 0 && objIdx < (int)objs.size();
}

static bool f66_volIdxValid(int objIdx, int volIdx) {
    if (!f66_objIdxValid(objIdx)) return false;
    const auto& vols = getGlobalModel().objects[objIdx]->volumes;
    return volIdx >= 0 && volIdx < (int)vols.size();
}

bool SlicerEngine::isObjectSplittable(int objIdx) const {
    if (!f66_objIdxValid(objIdx)) return false;
    auto* obj = getGlobalModel().objects[objIdx];
    return obj->parts_count() > 1;
}

bool SlicerEngine::isVolumeSplittable(int objIdx, int volIdx) const {
    if (!f66_volIdxValid(objIdx, volIdx)) return false;
    return getGlobalModel().objects[objIdx]->volumes[volIdx]->is_splittable();
}

std::optional<SlicerEngine::SplitResult> SlicerEngine::splitObject(int objIdx) {
    if (!f66_objIdxValid(objIdx)) return std::nullopt;
    Slic3r::Model& model = getGlobalModel();

    // ModelObject::split appends each new piece to m_model->objects via
    // m_model->add_object() (Model.cpp:2020). On a non-splittable input it
    // still emits one trivial copy. We collect the appended pointers,
    // remove them from the back of model.objects, delete the original via
    // the Model API (which is friend of ~ModelObject), and insert the new
    // pieces in the original's slot — but only if there's more than one.
    const size_t before_size = model.objects.size();
    Slic3r::ModelObjectPtrs new_objects;
    model.objects[objIdx]->split(&new_objects);
    const size_t added = new_objects.size();

    if (added <= 1) {
        // Undo the trivial copy split() appended (if any) and report no-split.
        // The appended objects sit at indices [before_size .. before_size + added).
        for (size_t i = 0; i < added; ++i) {
            model.delete_object(model.objects.size() - 1);  // pop & delete the last
        }
        return std::nullopt;
    }

    // The new pieces are contiguous at indices [before_size .. before_size + added).
    // Pull them out of the vector without deleting (raw-pointer manipulation),
    // delete the original via Model API, then insert the new pieces at objIdx.
    std::vector<Slic3r::ModelObject*> tail_ptrs;
    tail_ptrs.reserve(added);
    for (size_t i = 0; i < added; ++i) {
        tail_ptrs.push_back(model.objects[before_size + i]);
    }
    model.objects.erase(model.objects.begin() + before_size,
                        model.objects.begin() + before_size + added);

    // Now original sits at objIdx. delete_object properly invokes ~ModelObject.
    model.delete_object(static_cast<size_t>(objIdx));

    // Insert the new pieces at objIdx.
    model.objects.insert(model.objects.begin() + objIdx,
                        tail_ptrs.begin(), tail_ptrs.end());

    invalidatePreviewMeshCache();
    SAPIL_LOGI("splitObject: split %d into %d new objects", objIdx, (int)added);
    return SplitResult{objIdx, (int)added};
}

int SlicerEngine::splitVolume(int objIdx, int volIdx) {
    if (!f66_volIdxValid(objIdx, volIdx)) return -1;
    auto* obj = getGlobalModel().objects[objIdx];
    constexpr unsigned MAX_EXTRUDERS = 4; // U1 hardware limit
    obj->volumes[volIdx]->split(MAX_EXTRUDERS);
    invalidatePreviewMeshCache();
    return (int)obj->volumes.size();
}

std::optional<std::array<double, 3>> SlicerEngine::autoOrientObject(int objIdx) {
    if (!f66_objIdxValid(objIdx)) return std::nullopt;
    auto* obj = getGlobalModel().objects[objIdx];
    if (obj->instances.empty()) return std::nullopt;
    try {
        Slic3r::orientation::orient(obj);
    } catch (...) {
        SAPIL_LOGE("autoOrientObject: orient threw on object %d", objIdx);
        return std::nullopt;
    }
    obj->invalidate_bounding_box();
    Slic3r::Vec3d rot = obj->instances[0]->get_rotation();
    invalidatePreviewMeshCache();
    return std::array<double, 3>{rot.x(), rot.y(), rot.z()};
}

int SlicerEngine::autoOrientAll() {
    if (!isModelLoaded()) return 0;
    int succeeded = 0;
    for (auto* obj : getGlobalModel().objects) {
        if (obj->instances.empty()) continue;
        try {
            Slic3r::orientation::orient(obj);
            obj->invalidate_bounding_box();
            succeeded++;
        } catch (...) {
            // skip degenerate objects
        }
    }
    if (succeeded > 0) invalidatePreviewMeshCache();
    return succeeded;
}

bool SlicerEngine::setObjectRotation(int objIdx, float rxDeg, float ryDeg, float rzDeg) {
    if (!f66_objIdxValid(objIdx)) return false;
    auto* obj = getGlobalModel().objects[objIdx];
    if (obj->instances.empty()) return false;
    constexpr double DEG2RAD = 3.14159265358979323846 / 180.0;
    obj->instances[0]->set_rotation(Slic3r::Vec3d(
        rxDeg * DEG2RAD, ryDeg * DEG2RAD, rzDeg * DEG2RAD));
    obj->invalidate_bounding_box();
    invalidatePreviewMeshCache();
    return true;
}

std::array<float, 3> SlicerEngine::getObjectRotation(int objIdx) const {
    std::array<float, 3> out{0.f, 0.f, 0.f};
    if (!f66_objIdxValid(objIdx)) return out;
    auto* obj = getGlobalModel().objects[objIdx];
    if (obj->instances.empty()) return out;
    constexpr double RAD2DEG = 180.0 / 3.14159265358979323846;
    Slic3r::Vec3d rot = obj->instances[0]->get_rotation();
    out[0] = static_cast<float>(rot.x() * RAD2DEG);
    out[1] = static_cast<float>(rot.y() * RAD2DEG);
    out[2] = static_cast<float>(rot.z() * RAD2DEG);
    return out;
}

bool SlicerEngine::setObjectScale(int objIdx, float sx, float sy, float sz) {
    if (!f66_objIdxValid(objIdx)) return false;
    auto* obj = getGlobalModel().objects[objIdx];
    if (obj->instances.empty()) return false;
    obj->instances[0]->set_scaling_factor(Slic3r::Vec3d(sx, sy, sz));
    obj->invalidate_bounding_box();
    invalidatePreviewMeshCache();
    return true;
}

std::array<float, 3> SlicerEngine::getObjectScale(int objIdx) const {
    std::array<float, 3> out{1.f, 1.f, 1.f};
    if (!f66_objIdxValid(objIdx)) return out;
    auto* obj = getGlobalModel().objects[objIdx];
    if (obj->instances.empty()) return out;
    Slic3r::Vec3d s = obj->instances[0]->get_scaling_factor();
    out[0] = static_cast<float>(s.x());
    out[1] = static_cast<float>(s.y());
    out[2] = static_cast<float>(s.z());
    return out;
}

std::string SlicerEngine::getObjectName(int objIdx) const {
    if (!f66_objIdxValid(objIdx)) return "";
    return getGlobalModel().objects[objIdx]->name;
}

std::string SlicerEngine::getVolumeName(int objIdx, int volIdx) const {
    if (!f66_volIdxValid(objIdx, volIdx)) return "";
    return getGlobalModel().objects[objIdx]->volumes[volIdx]->name;
}

int SlicerEngine::getVolumeExtruder(int objIdx, int volIdx) const {
    if (!f66_volIdxValid(objIdx, volIdx)) return 0;
    return getGlobalModel().objects[objIdx]->volumes[volIdx]->extruder_id();
}

bool SlicerEngine::setVolumeExtruder(int objIdx, int volIdx, int slot) {
    if (!f66_volIdxValid(objIdx, volIdx)) return false;
    if (slot < 1 || slot > 16) return false;  // sanity bound
    auto* vol = getGlobalModel().objects[objIdx]->volumes[volIdx];
    vol->config.set_key_value("extruder", new Slic3r::ConfigOptionInt(slot));
    return true;
}

} // namespace sapil
