#include "../include/sapil.h"
#include "sapil_internal.h"
#include "sapil_diagnostics.h"
#include <fstream>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <numeric>
#include <regex>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <thread>

// PrusaSlicer includes
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/Format/STL.hpp"
#include "libslic3r/Format/3mf.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/Format/OBJ.hpp"
#include "libslic3r/Format/STEP.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/TriangleSelector.hpp"
#include "libslic3r/QuadricEdgeCollapse.hpp"
#include "libslic3r/Semver.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/Slicing.hpp"
#include <tbb/parallel_for.h>

// miniz for direct ZIP extraction of project_settings.config
#include "miniz.h"

// =============================================================================
// sapil_model.cpp — Model loading using PrusaSlicer's Slic3r::Model
// =============================================================================

namespace sapil {

// Persistent model state
Slic3r::Model g_model;                             // exposed to sapil_bambu_snapshot.cpp
static Slic3r::DynamicPrintConfig g_model_config;  // Config from 3MF project_settings.config
ModelInfo g_model_info;                            // exposed to sapil_bambu_snapshot.cpp
// Bambu-specific out-params captured from Slic3r::Model::read_from_file.
// Non-static so sapil_bambu_snapshot.cpp can extern them. Reset each load.
Slic3r::PlateDataPtrs g_plate_data_list;           // exposed to sapil_bambu_snapshot.cpp
bool g_is_bbl = false;                             // exposed to sapil_bambu_snapshot.cpp
Slic3r::Semver g_file_version;                     // exposed to sapil_bambu_snapshot.cpp
static bool g_model_loaded = false;
static std::string g_files_dir;  // App files directory, derived from model path
static std::vector<std::vector<int>> g_model_preview_extruders;
static PreviewMesh g_cached_preview_mesh;
static bool g_preview_mesh_valid = false;
// F54 fix36: per-volume triangle counts in mesh-build order. Populated alongside the cached
// preview mesh during getPreparePreviewMesh; consumed by nativeGetPreviewVolumeTriangleCounts.
// Drives AI Paint cascade Branch B (per-volume) by giving Kotlin an explicit triangle→volume
// attribution map.
static std::vector<int> g_preview_volume_triangle_counts;
// F142 preview-scene follow-up: triangle counts per native preview batch, in build order.
// These batches currently align with the existing per-volume append units plus the trailing
// modifier block, and give Kotlin a stable seam to migrate toward staged/progressive preview
// uploads without changing the current single-mesh UI contract.
static std::vector<int> g_preview_batch_triangle_counts;
static thread_local std::function<void(const PreviewMesh&)> g_prepare_preview_batch_callback;
// F95: triangle index where the trailing negative/modifier-volume block begins in the cached
// preview mesh, or -1 when the model has none. Set alongside the cached mesh during
// getPreparePreviewMesh; consumed by nativeGetPreviewModifierBlockStart so the Android renderer
// can draw [start, end) translucent.
static int g_preview_modifier_block_start = -1;
static std::atomic<bool> g_preview_cancel{false};

// Base instance positions captured on first setModelRotation call.
// Cleared on model load/clear. Used to avoid positional drift across repeated
// slider calls (each call rotates from the original positions, not current ones).
static std::vector<Slic3r::Vec3d> g_rotation_base_positions;
// Original per-instance rotations captured on first setModelRotation call.
// The user's rotation is composed on top of these base rotations so that
// embedded 3MF rotations (e.g. 90° Z from build-item transform) are preserved.
static std::vector<Slic3r::Vec3d> g_rotation_base_rotations;

static int locateZipEntry(mz_zip_archive& zip, const char* exact_path)
{
    int idx = mz_zip_reader_locate_file(&zip, exact_path, nullptr, 0);
    if (idx >= 0) return idx;

    mz_zip_archive_file_stat stat;
    const std::string target(exact_path);
    for (mz_uint i = 0; i < mz_zip_reader_get_num_files(&zip); ++i) {
        if (!mz_zip_reader_file_stat(&zip, i, &stat)) continue;
        std::string name(stat.m_filename);
        std::replace(name.begin(), name.end(), '\\', '/');
        if (name == target) return static_cast<int>(i);
        std::string lowered_name = name;
        std::string lowered_target = target;
        std::transform(lowered_name.begin(), lowered_name.end(), lowered_name.begin(), ::tolower);
        std::transform(lowered_target.begin(), lowered_target.end(), lowered_target.begin(), ::tolower);
        if (lowered_name == lowered_target) return static_cast<int>(i);
    }
    return -1;
}

static std::vector<std::vector<int>> parsePreviewExtrudersFromModelConfig(const std::string& xml)
{
    std::vector<std::vector<int>> object_extruders;
    const std::regex object_regex(R"cfg(<object\b[^>]*>[\s\S]*?</object>)cfg");
    const std::regex part_regex(R"cfg(<part\b[^>]*>[\s\S]*?</part>)cfg");
    const std::regex extruder_regex(R"cfg(<metadata\b[^>]*key="extruder"\b[^>]*value="(\d+)")cfg");

    for (std::sregex_iterator object_it(xml.begin(), xml.end(), object_regex), end; object_it != end; ++object_it) {
        const std::string object_block = object_it->str();
        std::vector<int> part_extruders;

        for (std::sregex_iterator part_it(object_block.begin(), object_block.end(), part_regex); part_it != end; ++part_it) {
            const std::string part_block = part_it->str();
            std::smatch extruder_match;
            if (std::regex_search(part_block, extruder_match, extruder_regex)) {
                const int extruder = std::max(1, std::stoi(extruder_match[1].str()));
                part_extruders.push_back(extruder);
            }
        }

        if (!part_extruders.empty()) {
            object_extruders.push_back(std::move(part_extruders));
            continue;
        }

        std::string object_block_without_parts = std::regex_replace(object_block, part_regex, "");
        std::smatch extruder_match;
        if (std::regex_search(object_block_without_parts, extruder_match, extruder_regex)) {
            object_extruders.push_back({ std::max(1, std::stoi(extruder_match[1].str())) });
        } else {
            object_extruders.emplace_back();
        }
    }

    return object_extruders;
}

bool SlicerEngine::loadModel(const std::string& filepath) {
    return loadModel(filepath, 0);
}

bool SlicerEngine::loadModel(const std::string& filepath, int plate_id) {
    SAPIL_LOGI("Loading model: %s (plate_id=%d)", filepath.c_str(), plate_id);
    g_model_preview_extruders.clear();

    // Reset Bambu diff-harness out-params so a previous load can't leak state.
    Slic3r::release_PlateData_list(g_plate_data_list);
    g_plate_data_list.clear();
    g_is_bbl = false;
    g_file_version = Slic3r::Semver();

    // Determine format from extension
    std::string ext = filepath.substr(filepath.find_last_of('.') + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);

    if (ext != "stl" && ext != "3mf" && ext != "obj" && ext != "step" && ext != "stp") {
        SAPIL_LOGE("Unsupported file format: %s", ext.c_str());
        return false;
    }

    // Check file exists
    std::ifstream f(filepath);
    if (!f.good()) {
        SAPIL_LOGE("File not found: %s", filepath.c_str());
        return false;
    }
    f.close();

    try {
        // Use PrusaSlicer's Model::read_from_file
        Slic3r::DynamicPrintConfig config;
        Slic3r::ConfigSubstitutionContext config_substitutions(Slic3r::ForwardCompatibilitySubstitutionRule::Enable);

        // Capture plate_data_list / is_bbl / file_version out-params for the
        // Bambu differential harness (sapil_bambu_snapshot.cpp reads them via extern).
        // project_presets is unused here but required to advance the arg list.
        //
        // Phase 1 sub-plan #2b: plate_id > 0 causes the BBS importer to filter
        // objects to m_plater_data[plate_id].obj_inst_map at ingestion
        // (bbs_3mf.cpp:1921-1940). plate_id = 0 remains the "load all plates"
        // default for STL / OBJ / STEP and for the existing loadModel(path)
        // overload.
        std::vector<Slic3r::Preset*> project_presets;
        g_model = Slic3r::Model::read_from_file(
            filepath, &config, &config_substitutions,
            Slic3r::LoadStrategy::LoadModel | Slic3r::LoadStrategy::LoadConfig | Slic3r::LoadStrategy::AddDefaultInstances,
            &g_plate_data_list, &project_presets, &g_is_bbl, &g_file_version,
            /*proFn=*/nullptr, /*stlFn=*/nullptr, /*project=*/nullptr, plate_id);

        // Store the embedded config (from 3MF project_settings.config).
        // This contains machine_start_gcode, change_filament_gcode, and all profile
        // settings embedded by ProfileEmbedder.  sapil_print.cpp uses this as the base
        // config for slicing, overlaying user SliceConfig on top.
        //
        // OrcaSlicer's BBS 3MF reader may fail to extract the config on Android
        // (backup_path / temp directory issues), so we fall back to direct ZIP extraction.
        if (config.empty() && ext == "3mf") {
            SAPIL_LOGI("BBS reader returned empty config — extracting project_settings.config directly");
            mz_zip_archive zip;
            mz_zip_zero_struct(&zip);
            if (mz_zip_reader_init_file(&zip, filepath.c_str(), 0)) {
                int idx = locateZipEntry(zip, "Metadata/project_settings.config");
                if (idx >= 0) {
                    size_t uncomp_size = 0;
                    void* data = mz_zip_reader_extract_to_heap(&zip, idx, &uncomp_size, 0);
                    if (data && uncomp_size > 0) {
                        // Write to a temp file for load_from_json
                        std::string tmp_path = filepath + ".config.tmp";
                        std::ofstream tmp(tmp_path, std::ios::binary);
                        tmp.write(static_cast<const char*>(data), uncomp_size);
                        tmp.close();
                        mz_free(data);

                        std::map<std::string, std::string> key_values;
                        std::string reason;
                        Slic3r::ConfigSubstitutionContext subs(Slic3r::ForwardCompatibilitySubstitutionRule::Enable);
                        int ret = config.load_from_json(tmp_path, subs, true, key_values, reason);
                        std::remove(tmp_path.c_str());
                        if (ret == 0) {
                            SAPIL_LOGI("Direct extraction: loaded %zu config keys", config.keys().size());
                        } else {
                            SAPIL_LOGW("Direct extraction: load_from_json failed: %s", reason.c_str());
                        }
                    } else {
                        if (data) mz_free(data);
                        SAPIL_LOGW("Direct extraction: failed to extract entry");
                    }
                } else {
                    SAPIL_LOGI("No Metadata/project_settings.config in 3MF");
                }
                int model_cfg_idx = locateZipEntry(zip, "Metadata/model_settings.config");
                if (model_cfg_idx >= 0) {
                    size_t model_cfg_size = 0;
                    void* model_cfg_data = mz_zip_reader_extract_to_heap(&zip, model_cfg_idx, &model_cfg_size, 0);
                    if (model_cfg_data && model_cfg_size > 0) {
                        g_model_preview_extruders = parsePreviewExtrudersFromModelConfig(
                            std::string(static_cast<const char*>(model_cfg_data), model_cfg_size)
                        );
                        SAPIL_LOGI(
                            "Loaded preview extruder fallback for %zu object(s) from model_settings.config",
                            g_model_preview_extruders.size()
                        );
                    }
                    if (model_cfg_data) mz_free(model_cfg_data);
                } else {
                    SAPIL_LOGI("No Metadata/model_settings.config in 3MF");
                }
                mz_zip_reader_end(&zip);
            }
        }
        g_model_config = config;
        SAPIL_LOGI("Stored embedded config with %zu keys", g_model_config.keys().size());

        if (g_model.objects.empty()) {
            SAPIL_LOGE("No objects found in file");
            return false;
        }

        // Store the files directory from the model path
        auto last_sep = filepath.find_last_of("/\\");
        g_files_dir = (last_sep != std::string::npos) ? filepath.substr(0, last_sep) : ".";

        // Extract model info
        g_model_info.filename = filepath.substr(last_sep + 1);
        g_model_info.format = ext;

        // Calculate bounding box across all objects.
        // Invalidate cached bounding boxes first — the 3MF reader may populate them
        // before instance transforms (rotation, translation) are fully applied, leaving
        // stale pre-rotation dimensions. Force recomputation so size_x/size_y reflect
        // the actual world-space footprint (e.g. a 90°-rotated build item has its X/Y swapped).
        for (auto* obj : g_model.objects) {
            obj->invalidate_bounding_box();
        }

        Slic3r::BoundingBoxf3 bb;
        int total_triangles = 0;
        int total_volumes = 0;
        bool all_manifold = true;

        for (const auto* obj : g_model.objects) {
            for (const auto* vol : obj->volumes) {
                total_volumes++;
                const auto& mesh = vol->mesh();
                total_triangles += mesh.facets_count();
                if (!mesh.stats().manifold()) {
                    all_manifold = false;
                }

            }
            bb.merge(obj->bounding_box_exact());
        }

        Slic3r::Vec3d size = bb.size();
        g_model_info.size_x = static_cast<float>(size.x());
        g_model_info.size_y = static_cast<float>(size.y());
        g_model_info.size_z = static_cast<float>(size.z());
        g_model_info.triangle_count = total_triangles;
        g_model_info.volume_count = total_volumes;
        g_model_info.is_manifold = all_manifold;

        g_model_loaded = true;
        g_preview_mesh_valid = false;
        g_rotation_base_positions.clear();
        g_rotation_base_rotations.clear();
        { extern void resetLastRotation(); resetLastRotation(); }
        { extern void resetLoadTimeScaleFactors(); resetLoadTimeScaleFactors(); }

        SAPIL_LOGI("Model loaded: %s (%s) — %.1f x %.1f x %.1f mm, %d triangles",
            g_model_info.filename.c_str(), ext.c_str(),
            g_model_info.size_x, g_model_info.size_y, g_model_info.size_z,
            g_model_info.triangle_count);

        return true;

    } catch (const std::exception& e) {
        SAPIL_LOGE("Failed to load model: %s", e.what());
        g_model_loaded = false;
        return false;
    }
}

ModelInfo SlicerEngine::getModelInfo() const {
    return g_model_info;
}

static void appendItsPreviewMesh(
    PreviewMesh& out,
    const indexed_triangle_set& its,
    uint8_t extruder_index,
    int stride,
    int& tri_counter
) {
    bool logged_invalid_index = false;
    bool logged_invalid_vertex = false;
    for (const auto& tri : its.indices) {
        // Validate indices before counting toward stride
        bool valid = true;
        for (int i = 0; i < 3; ++i) {
            const int vertex_index = tri[i];
            if (vertex_index < 0 || static_cast<size_t>(vertex_index) >= its.vertices.size()) {
                if (!logged_invalid_index) {
                    SAPIL_LOGW(
                        "preview triangle skipped: invalid vertex index %d (vertex count=%zu)",
                        vertex_index,
                        its.vertices.size()
                    );
                    logged_invalid_index = true;
                }
                valid = false;
                break;
            }
        }
        if (!valid) continue;

        const auto& v0 = its.vertices[tri[0]];
        const auto& v1 = its.vertices[tri[1]];
        const auto& v2 = its.vertices[tri[2]];

        // Check finite
        for (const auto* vp : {&v0, &v1, &v2}) {
            if (!std::isfinite(vp->x()) || !std::isfinite(vp->y()) || !std::isfinite(vp->z())) {
                if (!logged_invalid_vertex) {
                    SAPIL_LOGW(
                        "preview triangle skipped: non-finite vertex [%.3f,%.3f,%.3f]",
                        vp->x(), vp->y(), vp->z()
                    );
                    logged_invalid_vertex = true;
                }
                valid = false;
                break;
            }
        }
        if (!valid) continue;

        // Skip degenerate (zero/near-zero area) triangles before counting toward stride.
        // This ensures stride distributes evenly over real geometry, not sliver walls.
        const Eigen::Vector3f e1 = v1 - v0;
        const Eigen::Vector3f e2 = v2 - v0;
        const Eigen::Vector3f cross = e1.cross(e2);
        if (cross.squaredNorm() < 1e-12f) continue;

        // Stride over valid non-degenerate triangles
        if (stride > 1 && (tri_counter % stride != 0)) { ++tri_counter; continue; }
        ++tri_counter;

        out.triangle_positions.push_back(v0.x()); out.triangle_positions.push_back(v0.y()); out.triangle_positions.push_back(v0.z());
        out.triangle_positions.push_back(v1.x()); out.triangle_positions.push_back(v1.y()); out.triangle_positions.push_back(v1.z());
        out.triangle_positions.push_back(v2.x()); out.triangle_positions.push_back(v2.y()); out.triangle_positions.push_back(v2.z());
        out.extruder_indices.push_back(extruder_index);
    }
}

static void compactPreviewIndices(PreviewMesh& mesh) {
    if (mesh.extruder_indices.empty()) return;

    std::vector<uint8_t> unique_indices = mesh.extruder_indices;
    std::sort(unique_indices.begin(), unique_indices.end());
    unique_indices.erase(std::unique(unique_indices.begin(), unique_indices.end()), unique_indices.end());

    std::vector<uint8_t> lut(256, 0);
    for (size_t i = 0; i < unique_indices.size(); ++i) {
        lut[unique_indices[i]] = static_cast<uint8_t>(i);
    }

    for (uint8_t& idx : mesh.extruder_indices) {
        idx = lut[idx];
    }
}

static PreviewMesh slicePreviewMesh(const PreviewMesh& src, size_t start_triangle, size_t triangle_count) {
    PreviewMesh slice;
    if (triangle_count == 0) return slice;
    const size_t pos_start = start_triangle * 9;
    const size_t pos_count = triangle_count * 9;
    const size_t idx_start = start_triangle;
    const size_t idx_count = triangle_count;
    if (pos_start + pos_count > src.triangle_positions.size() ||
        idx_start + idx_count > src.extruder_indices.size()) {
        return PreviewMesh();
    }
    slice.triangle_positions.insert(
        slice.triangle_positions.end(),
        src.triangle_positions.begin() + static_cast<std::ptrdiff_t>(pos_start),
        src.triangle_positions.begin() + static_cast<std::ptrdiff_t>(pos_start + pos_count));
    slice.extruder_indices.insert(
        slice.extruder_indices.end(),
        src.extruder_indices.begin() + static_cast<std::ptrdiff_t>(idx_start),
        src.extruder_indices.begin() + static_cast<std::ptrdiff_t>(idx_start + idx_count));
    return slice;
}

static void emitPreparePreviewBatch(const PreviewMesh& mesh, size_t start_triangle, size_t triangle_count) {
    if (triangle_count == 0) return;
    if (!g_prepare_preview_batch_callback) return;
    g_prepare_preview_batch_callback(slicePreviewMesh(mesh, start_triangle, triangle_count));
}

PreviewMesh SlicerEngine::getPreparePreviewMesh(int max_triangles) const {
    PreviewMesh out;
    if (!g_model_loaded) {
        return out;
    }

    // B55: reset cancel flag at start of each preview computation
    g_preview_cancel.store(false, std::memory_order_release);

    // Return cached result if available — avoids expensive re-decimation on tab switch
    if (g_preview_mesh_valid) {
        SAPIL_LOGI("getPreparePreviewMesh: returning cached result (%zu tris)",
            g_cached_preview_mesh.extruder_indices.size());
        return g_cached_preview_mesh;
    }

    // Count total triangles across all printable volumes to compute stride
    int total_tris = 0;
    for (const auto* object : g_model.objects) {
        if (object == nullptr || !object->printable) continue;
        if (object->instances.empty()) continue;
        for (const auto* volume : object->volumes) {
            if (volume == nullptr || !volume->is_model_part()) continue;
            if (!volume->mmu_segmentation_facets.empty()) {
                std::vector<indexed_triangle_set> facets_per_type;
                volume->mmu_segmentation_facets.get_facets(*volume, facets_per_type);
                for (const auto& its : facets_per_type) {
                    total_tris += static_cast<int>(its.indices.size());
                }
            } else {
                total_tris += static_cast<int>(volume->mesh().its.indices.size());
            }
        }
    }

    // Flat models (height < 5% of footprint) carry nearly all their geometry as surface
    // detail — text, track outlines, raised features. The standard 100K budget collapses
    // this detail to nothing. Use 500K for flat models so fine features survive QEM.
    // F1 calendar: 210×250×6mm → height_ratio = 6/250 = 0.024 → flat → 500K budget.
    const float height_ratio = (g_model_info.size_x > 0 && g_model_info.size_y > 0)
        ? g_model_info.size_z / std::max(g_model_info.size_x, g_model_info.size_y)
        : 1.0f;
    const bool is_flat_model = height_ratio < 0.05f;
    const int default_max = is_flat_model ? 500000 : 100000;
    const int effective_max = (max_triangles > 0) ? max_triangles : default_max;
    const bool needs_decimation = total_tris > effective_max;
    // QEM per-volume: apply QEM to any volume that is ≤2M triangles on its own.
    // This handles large models composed of many smaller volumes (e.g. F1 calendar:
    // 8M total tris across 7 volumes of ~1M each — global check would force stride,
    // but per-volume check allows QEM on each piece for clean geometry).
    // A 10-second wall-clock budget prevents pathological cases: if QEM is taking
    // too long, remaining volumes fall back to stride decimation.
    const int stride = needs_decimation
        ? ((total_tris + effective_max - 1) / effective_max)
        : 1;
    const auto qem_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    bool qem_budget_exceeded = false;

    SAPIL_LOGI("getPreparePreviewMesh: total_tris=%d max=%d stride=%d flat=%s height_ratio=%.3f",
        total_tris, effective_max, stride, is_flat_model ? "yes" : "no", height_ratio);

    bool has_mmu_data = false;  // B46: track if any MMU data present
    size_t object_index = 0;
    // fix36: reset per-volume triangle-count map; populated as each volume appends.
    std::vector<int> volume_tri_counts;
    std::vector<int> batch_tri_counts;
    for (const auto* object : g_model.objects) {
        if (object == nullptr || !object->printable) continue;
        if (object->instances.empty()) continue;
        // B55: check cancel flag between objects
        if (g_preview_cancel.load(std::memory_order_acquire)) {
            SAPIL_LOGI("getPreparePreviewMesh: cancelled during object iteration");
            return PreviewMesh();
        }
        const std::vector<int>* preview_extruders =
            object_index < g_model_preview_extruders.size() ? &g_model_preview_extruders[object_index] : nullptr;

        for (const auto* instance : object->instances) {
            if (instance == nullptr || !instance->printable) continue;
            const Slic3r::Transform3d instance_matrix = instance->get_matrix();
            size_t volume_index = 0;

            for (const auto* volume : object->volumes) {
                if (volume == nullptr || !volume->is_model_part()) continue;

                // fix36: snapshot the output index BEFORE appending this volume's triangles
                // so we can compute how many triangles this volume contributed (works for
                // both MMU and non-MMU paths regardless of which branch ran).
                const size_t pre_append_indices = out.extruder_indices.size();

                int fallback_extruder = volume->extruder_id();
                if (preview_extruders != nullptr && volume_index < preview_extruders->size() &&
                    (*preview_extruders)[volume_index] > 0) {
                    fallback_extruder = (*preview_extruders)[volume_index];
                }
                if (fallback_extruder <= 0) fallback_extruder = 1;
                const uint8_t fallback_index = static_cast<uint8_t>(std::max(0, fallback_extruder - 1));

                if (!volume->mmu_segmentation_facets.empty()) {
                    has_mmu_data = true;
                    std::vector<indexed_triangle_set> facets_per_type;
                    volume->mmu_segmentation_facets.get_facets(*volume, facets_per_type);

                    // B51: apply both volume AND instance transforms, matching the
                    // non-MMU path.  B46 removed instance_matrix ("Kotlin handles
                    // bed positioning") but this left MMU volumes in model-local
                    // coords while non-MMU volumes are in world coords — causing
                    // wrong orientation (old.3mf lying flat, Korok upright).
                    //
                    // Emit per-state triangles via appendItsPreviewMesh (proper
                    // degenerate filtering) into per-state temp buffers, then
                    // round-robin interleave (B48) so all paint colours are
                    // proportionally represented even if GL truncates.
                    //
                    // B131: apply the global stride to each per-state append.
                    // Previously stride=1 was hard-coded, so MMU/paint-state
                    // files bypassed max_triangles entirely. Hueforge-class
                    // files (e.g. GhostfacePokemoncard.3mf at 3.7M tris with
                    // paint data on every volume) returned the full triangle
                    // count instead of capping at 100K — causing slow GL
                    // upload and "Prepare doesn't show the model" UX (because
                    // mesh upload + decimation accounted for several seconds
                    // before any frame could render).
                    // B131 follow-up (dots / empty-plate regression). Paint states are
                    // ARBITRARY, frequently non-manifold triangle subsets of the volume, so
                    // BOTH decimation attempts corrupt them:
                    //   - stride-skip (B131 v2.10.2) keeps only ~1/stride of the facets →
                    //     scattered triangles → "loads of dots" / holes (colored Benchy);
                    //   - QEM edge-collapse explodes/erodes non-manifold patches →
                    //     ~±30000mm vertices → model off-screen → "empty plate" (H2C Benchy).
                    // So keep the FULL solid mesh (stride=1) for normal painted files, and
                    // only stride-skip genuinely enormous FLAT Hueforge-class volumes (>2M tris,
                    // e.g. GhostfacePokemoncard 3.7M) where an uncapped upload would stall the
                    // UI — there the bounded-but-sparse fallback keeps it on-bed and under the
                    // B131 triangle cap. Tall painted figures like Chubby Darth Vader can also
                    // exceed 2M tris, but their preview must stay solid; sparse stride-skip turns
                    // the body into disconnected dots.
                    // A solid colour-preserving decimation for huge paint
                    // files (QEM the full manifold mesh + re-derive paint) is a tracked
                    // follow-up; common painted models comfortably fit uncapped.
                    // Keyed on the GLOBAL model triangle total, not this volume's — a
                    // Hueforge file (Ghostface 3.7M) spreads its triangles across many
                    // painted volumes that are each individually small, so a per-volume
                    // threshold would never trip and the model would upload uncapped.
                    // ALSO gated on flatness so non-flat multi-colour figures don't regress
                    // back to the scattered-dots failure mode.
                    const int MMU_SOLID_LIMIT = 2000000;
                    const bool mmu_needs_decimation = needs_decimation &&
                        is_flat_model &&
                        total_tris > MMU_SOLID_LIMIT;
                    const int mmu_stride = mmu_needs_decimation ? stride : 1;
                    struct StateMesh {
                        std::vector<float> positions;   // 9 floats per tri
                        std::vector<uint8_t> indices;   // 1 per tri
                        size_t count() const { return indices.size(); }
                    };
                    std::vector<StateMesh> state_meshes;
                    for (size_t state_idx = 0; state_idx < facets_per_type.size(); ++state_idx) {
                        auto its = facets_per_type[state_idx];
                        if (its.indices.empty()) continue;
                        its_transform(its, volume->get_matrix(), true);
                        its_transform(its, instance_matrix, true);
                        const uint8_t extruder_index = state_idx == 0
                            ? fallback_index
                            : static_cast<uint8_t>(state_idx - 1);
                        // Emit into a temporary PreviewMesh to get degenerate filtering
                        PreviewMesh tmp;
                        int tri_counter = 0;
                        appendItsPreviewMesh(tmp, its, extruder_index, mmu_stride, tri_counter);
                        if (!tmp.extruder_indices.empty()) {
                            StateMesh sm;
                            sm.positions = std::move(tmp.triangle_positions);
                            sm.indices = std::move(tmp.extruder_indices);
                            state_meshes.push_back(std::move(sm));
                        }
                    }

                    // Round-robin interleave: emit 1 triangle from each state in turn
                    int mmu_total = 0;
                    for (const auto& sm : state_meshes) mmu_total += static_cast<int>(sm.count());
                    SAPIL_LOGI("getPreparePreviewMesh MMU interleaved: %d tris, %zu states",
                        mmu_total, state_meshes.size());
                    std::vector<size_t> cursors(state_meshes.size(), 0);
                    bool any_left = true;
                    while (any_left) {
                        // B55: check cancel flag during MMU interleave
                        if (g_preview_cancel.load(std::memory_order_acquire)) {
                            SAPIL_LOGI("getPreparePreviewMesh: cancelled during MMU interleave");
                            return PreviewMesh();
                        }
                        any_left = false;
                        for (size_t si = 0; si < state_meshes.size(); ++si) {
                            if (cursors[si] < state_meshes[si].count()) {
                                size_t off = cursors[si] * 9;
                                for (int k = 0; k < 9; ++k)
                                    out.triangle_positions.push_back(state_meshes[si].positions[off + k]);
                                out.extruder_indices.push_back(state_meshes[si].indices[cursors[si]]);
                                ++cursors[si];
                                any_left = true;
                            }
                        }
                    }
                    batch_tri_counts.push_back(
                        static_cast<int>(out.extruder_indices.size() - pre_append_indices));
                    emitPreparePreviewBatch(
                        out,
                        pre_append_indices,
                        out.extruder_indices.size() - pre_append_indices);
                } else {
                    auto its = volume->mesh().its;
                    its_transform(its, volume->get_matrix(), true);
                    its_transform(its, instance_matrix, true);
                    const int vol_tris = static_cast<int>(its.indices.size());
                    // Only decimate volumes with enough triangles to be worth it.
                    // Small volumes (base plates, frames) must pass through unchanged
                    // so their shape is preserved. QEM uses a time budget, not a size cap.
                    const int MIN_DECIMATION_TRIS = 1000;
                    const bool vol_needs_decimation = needs_decimation && vol_tris > MIN_DECIMATION_TRIS;
                    const bool can_qem = vol_needs_decimation && !qem_budget_exceeded;
                    if (can_qem) {
                        const uint32_t target = static_cast<uint32_t>(
                            std::max(INT64_C(1),
                                static_cast<int64_t>(vol_tris) * effective_max / total_tris));
                        try {
                            if (its.indices.size() > target)
                                Slic3r::its_quadric_edge_collapse(its, target, nullptr,
                                    [&]() { if (g_preview_cancel.load(std::memory_order_acquire)) throw std::runtime_error("cancelled"); },
                                    nullptr);
                        } catch (const std::runtime_error&) {
                            SAPIL_LOGI("getPreparePreviewMesh: QEM cancelled mid-collapse");
                            return PreviewMesh();
                        }
                        if (std::chrono::steady_clock::now() > qem_deadline) {
                            qem_budget_exceeded = true;
                            SAPIL_LOGW("getPreparePreviewMesh: QEM time budget exceeded, switching to stride");
                        }
                    }
                    const int vol_stride = (can_qem || !vol_needs_decimation) ? 1 : stride;
                    int tri_counter = 0;
                    appendItsPreviewMesh(out, its, fallback_index, vol_stride, tri_counter);
                    batch_tri_counts.push_back(
                        static_cast<int>(out.extruder_indices.size() - pre_append_indices));
                    emitPreparePreviewBatch(
                        out,
                        pre_append_indices,
                        out.extruder_indices.size() - pre_append_indices);
                }
                // fix36: how many output triangles did this volume contribute? Stored in
                // mesh-build order — Kotlin pairs this with nativeGetAllVolumeExtruders to
                // form (objectIndex, volumeIndex, triangleStart, triangleCount) ranges.
                volume_tri_counts.push_back(
                    static_cast<int>(out.extruder_indices.size() - pre_append_indices));
                ++volume_index;
            }
        }
        ++object_index;
    }

    // B46: skip compaction for MMU meshes — the raw state_idx values are needed
    // by Kotlin's H2C index folding (% 4). Compaction would remap them to dense
    // 0-based indices, breaking the folding logic.
    if (!has_mmu_data) {
        compactPreviewIndices(out);
    }

    // F95: append negative-volume triangles as a contiguous trailing block so the
    // Android renderer can draw them translucent (desktop OrcaSlicer/PrusaSlicer parity).
    // These volumes are is_model_part()==false and are intentionally excluded from the
    // model-part passes above (and from total_tris/stride) — they are a Prepare-screen visual
    // aid only and are never sliced as solid here. Appended AFTER compactPreviewIndices so the
    // reserved tag isn't remapped, and AFTER the per-volume triangle-count map is built so AI
    // Paint's volumeRanges (model parts only) stay aligned. The Kotlin side identifies the
    // block by g_preview_modifier_block_start, not by the tag value, so MeshData.recolor's
    // out-of-range/255-clamp contract is unaffected.
    //
    // modifier-preview-fix (B141): scope the trailing block to NEGATIVE_VOLUME only.
    // The original F95 also included PARAMETER_MODIFIER, which broke B137's
    // auxFan_modifierVolume_preservedAsModifierPart test — the aux-fan-cover-hex 3MF
    // ships a 12-triangle Generic-Cube settings modifier (subtype="modifier_part") that
    // does not need a visual overlay (it carries print-setting overrides, not geometry
    // the user needs to see). F95's actual target is negative_part joint-clearance
    // cutters (see negative-volume-articulated.3mf fixture + the F95 commit copy
    // calling out "joint-clearance cutters"); the F95 test
    // getPreparePreviewMesh_emitsTranslucentModifierBlock_forNegativeVolumeFixture
    // uses only negative_part volumes and still passes with this scope. Restricting
    // the overlay to negatives matches desktop OrcaSlicer's behaviour (modifiers are
    // listed in the object tree but never get a translucent overlay) and restores the
    // pre-F95 contract that PARAMETER_MODIFIER volumes are entirely excluded from the
    // preview triangle stream.
    g_preview_modifier_block_start = -1;
    {
        const size_t model_part_tris = out.extruder_indices.size();
        const uint8_t MODIFIER_PREVIEW_INDEX = 0xFE;
        // B140: render modifier/negative volumes at FULL resolution (no stride). The global
        // model stride (e.g. 12 on a large model) keeps only every Nth triangle, which scatters
        // the cutter into disconnected "broken" fragments that can't be read as a shape — the
        // same failure mode as B136's MMU dots. These volumes are small (joint-clearance cutters)
        // and are a visual aid only, so keep them solid even when the model parts are decimated.
        const int mod_stride = 1;
        std::vector<std::pair<const void*, Slic3r::Transform3d>> rendered_modifiers;
        for (const auto* object : g_model.objects) {
            if (object == nullptr || !object->printable) continue;
            if (object->instances.empty()) continue;
            for (const auto* instance : object->instances) {
                if (instance == nullptr || !instance->printable) continue;
                const Slic3r::Transform3d instance_matrix = instance->get_matrix();
                for (const auto* volume : object->volumes) {
                    if (volume == nullptr) continue;
                    if (!volume->is_negative_volume()) continue;
                    auto its = volume->mesh().its;
                    if (its.indices.empty()) continue;
                    
                    Slic3r::Transform3d world_matrix = instance_matrix * volume->get_matrix();
                    const void* mesh_ptr = (const void*)its.vertices.data();
                    bool already_rendered = false;
                    for (const auto& rm : rendered_modifiers) {
                        if (rm.first == mesh_ptr && rm.second.isApprox(world_matrix, 1e-5)) {
                            already_rendered = true;
                            break;
                        }
                    }
                    if (already_rendered) continue;
                    rendered_modifiers.push_back({mesh_ptr, world_matrix});

                    its_transform(its, volume->get_matrix(), true);
                    its_transform(its, instance_matrix, true);
                    int tri_counter = 0;
                    appendItsPreviewMesh(out, its, MODIFIER_PREVIEW_INDEX, mod_stride, tri_counter);
                }
            }
        }
        if (out.extruder_indices.size() > model_part_tris) {
            g_preview_modifier_block_start = static_cast<int>(model_part_tris);
            batch_tri_counts.push_back(
                static_cast<int>(out.extruder_indices.size() - model_part_tris));
            emitPreparePreviewBatch(
                out,
                model_part_tris,
                out.extruder_indices.size() - model_part_tris);
            SAPIL_LOGI("getPreparePreviewMesh: appended modifier block start=%zu count=%zu",
                model_part_tris, out.extruder_indices.size() - model_part_tris);
        }
    }

    // Cache for instant return on tab switch
    g_cached_preview_mesh = out;
    g_preview_mesh_valid = true;
    // fix36: stash per-volume triangle counts alongside the cached mesh so
    // nativeGetPreviewVolumeTriangleCounts mirrors whatever the most recent build emitted.
    g_preview_volume_triangle_counts = std::move(volume_tri_counts);
    g_preview_batch_triangle_counts = std::move(batch_tri_counts);

    return out;
}

struct PrepareRenderBatch {
    std::vector<float> interleaved_geometry;
    std::vector<uint8_t> material_indices;
    float bounds[6] = {1e20f, 1e20f, 1e20f, -1e20f, -1e20f, -1e20f};
    int triangle_count = 0;

    static std::unique_ptr<PrepareRenderBatch> fromPreviewMesh(const PreviewMesh& mesh) {
        auto batch = std::make_unique<PrepareRenderBatch>();
        batch->triangle_count = mesh.extruder_indices.size();
        batch->material_indices = mesh.extruder_indices;
        // 10 floats per vertex (x,y,z, nx,ny,nz, r,g,b,a), 3 vertices per triangle = 30 floats
        batch->interleaved_geometry.resize(batch->triangle_count * 30);
        
        float* geom_data = batch->interleaved_geometry.data();

        tbb::parallel_for(tbb::blocked_range<int>(0, batch->triangle_count), [&](const tbb::blocked_range<int>& range) {
            for (int tri = range.begin(); tri < range.end(); ++tri) {
                int base = tri * 9;
                float x1 = mesh.triangle_positions[base];
                float y1 = mesh.triangle_positions[base + 1];
                float z1 = mesh.triangle_positions[base + 2];
                float x2 = mesh.triangle_positions[base + 3];
                float y2 = mesh.triangle_positions[base + 4];
                float z2 = mesh.triangle_positions[base + 5];
                float x3 = mesh.triangle_positions[base + 6];
                float y3 = mesh.triangle_positions[base + 7];
                float z3 = mesh.triangle_positions[base + 8];

                float ux = x2 - x1;
                float uy = y2 - y1;
                float uz = z2 - z1;
                float vx = x3 - x1;
                float vy = y3 - y1;
                float vz = z3 - z1;
                float nx0 = uy * vz - uz * vy;
                float ny0 = uz * vx - ux * vz;
                float nz0 = ux * vy - uy * vx;
                float len = std::sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0);
                if (len > 1e-8f) {
                    nx0 /= len; ny0 /= len; nz0 /= len;
                } else {
                    nx0 = 0.0f; ny0 = 0.0f; nz0 = 1.0f;
                }

                int out_base = tri * 30;
                // v0
                geom_data[out_base] = x1; geom_data[out_base + 1] = y1; geom_data[out_base + 2] = z1;
                geom_data[out_base + 3] = nx0; geom_data[out_base + 4] = ny0; geom_data[out_base + 5] = nz0;
                geom_data[out_base + 6] = 0.0f; geom_data[out_base + 7] = 0.0f; geom_data[out_base + 8] = 0.0f; geom_data[out_base + 9] = 0.0f;
                
                // v1
                geom_data[out_base + 10] = x2; geom_data[out_base + 11] = y2; geom_data[out_base + 12] = z2;
                geom_data[out_base + 13] = nx0; geom_data[out_base + 14] = ny0; geom_data[out_base + 15] = nz0;
                geom_data[out_base + 16] = 0.0f; geom_data[out_base + 17] = 0.0f; geom_data[out_base + 18] = 0.0f; geom_data[out_base + 19] = 0.0f;
                
                // v2
                geom_data[out_base + 20] = x3; geom_data[out_base + 21] = y3; geom_data[out_base + 22] = z3;
                geom_data[out_base + 23] = nx0; geom_data[out_base + 24] = ny0; geom_data[out_base + 25] = nz0;
                geom_data[out_base + 26] = 0.0f; geom_data[out_base + 27] = 0.0f; geom_data[out_base + 28] = 0.0f; geom_data[out_base + 29] = 0.0f;
            }
        });
        
        if (batch->triangle_count > 0) {
            float minX = 1e20f, minY = 1e20f, minZ = 1e20f;
            float maxX = -1e20f, maxY = -1e20f, maxZ = -1e20f;
            for (int tri = 0; tri < batch->triangle_count; ++tri) {
                int base = tri * 9;
                for (int v = 0; v < 3; ++v) {
                    float x = mesh.triangle_positions[base + v*3];
                    float y = mesh.triangle_positions[base + v*3 + 1];
                    float z = mesh.triangle_positions[base + v*3 + 2];
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    if (z < minZ) minZ = z;
                    if (z > maxZ) maxZ = z;
                }
            }
            batch->bounds[0] = minX; batch->bounds[1] = minY; batch->bounds[2] = minZ;
            batch->bounds[3] = maxX; batch->bounds[4] = maxY; batch->bounds[5] = maxZ;
        }

        return batch;
    }
};

struct PreparePreviewScene {
    std::mutex mutex;
    std::vector<std::unique_ptr<PrepareRenderBatch>> ready_batches;
    std::thread worker;
    std::atomic<bool> is_complete{false};

    ~PreparePreviewScene() {
        if (worker.joinable()) worker.join();
    }
    
    int getBatchCount() {
        std::lock_guard<std::mutex> lock(mutex);
        return ready_batches.size();
    }

    PrepareRenderBatch* getBatch(int index) {
        std::lock_guard<std::mutex> lock(mutex);
        if (index < 0 || index >= ready_batches.size()) return nullptr;
        return ready_batches[index].get();
    }
};

PreparePreviewSceneHandle SlicerEngine::buildPreparePreviewScene(int max_triangles) const {
    auto* scene = new PreparePreviewScene();

    if (g_preview_mesh_valid && !g_cached_preview_mesh.extruder_indices.empty()) {
        scene->ready_batches.push_back(PrepareRenderBatch::fromPreviewMesh(g_cached_preview_mesh));
        return scene;
    }

    scene->worker = std::thread([scene, max_triangles]() {
        try {
            g_prepare_preview_batch_callback = [scene](const PreviewMesh& batch) {
                if (batch.extruder_indices.empty()) return;
                auto render_batch = PrepareRenderBatch::fromPreviewMesh(batch);
                std::lock_guard<std::mutex> lock(scene->mutex);
                scene->ready_batches.push_back(std::move(render_batch));
            };
            SlicerEngine engine;
            engine.getPreparePreviewMesh(max_triangles);
        } catch (const std::exception& e) {
            SAPIL_LOGW("buildPreparePreviewScene worker failed: %s", e.what());
        } catch (...) {
            SAPIL_LOGW("buildPreparePreviewScene worker failed: unknown exception");
        }
        g_prepare_preview_batch_callback = nullptr;
        scene->is_complete = true;
    });

    return scene;
}

int SlicerEngine::getPreparePreviewSceneBatchCount(PreparePreviewSceneHandle scene) const {
    if (!scene) return 0;
    return scene->getBatchCount();
}

bool SlicerEngine::isPreparePreviewSceneComplete(PreparePreviewSceneHandle scene) const {
    if (!scene) return true;
    return scene->is_complete.load();
}

int SlicerEngine::getPreparePreviewSceneTriangleCount(PreparePreviewSceneHandle scene, int batch_index) const {
    if (!scene) return 0;
    auto* batch = scene->getBatch(batch_index);
    return batch ? batch->triangle_count : 0;
}

const float* SlicerEngine::getPreparePreviewSceneGeometryBuffer(PreparePreviewSceneHandle scene, int batch_index) const {
    if (!scene) return nullptr;
    auto* batch = scene->getBatch(batch_index);
    return batch ? batch->interleaved_geometry.data() : nullptr;
}

const uint8_t* SlicerEngine::getPreparePreviewSceneMaterialBuffer(PreparePreviewSceneHandle scene, int batch_index) const {
    if (!scene) return nullptr;
    auto* batch = scene->getBatch(batch_index);
    return batch ? batch->material_indices.data() : nullptr;
}

const float* SlicerEngine::getPreparePreviewSceneBoundingBox(PreparePreviewSceneHandle scene, int batch_index) const {
    if (!scene) return nullptr;
    auto* batch = scene->getBatch(batch_index);
    return batch ? batch->bounds : nullptr;
}

void SlicerEngine::releasePreparePreviewScene(PreparePreviewSceneHandle scene) const {
    delete scene;
}

// fix36: accessor for the per-volume triangle-count map captured during the most recent
// getPreparePreviewMesh build. Returns the counts in mesh-build order (same as the volume
// iteration in nativeGetAllVolumeExtruders). Kotlin combines this with the volume metadata
// JSON to attribute each preview-mesh triangle to a volume → enables AI Paint cascade
// Branch B.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPreviewVolumeTriangleCounts(
        JNIEnv* env, jobject) {
    if (g_preview_volume_triangle_counts.empty()) return nullptr;
    const auto& counts = g_preview_volume_triangle_counts;
    jintArray result = env->NewIntArray(static_cast<jsize>(counts.size()));
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(counts.size()), counts.data());
    return result;
}

// F142: triangle counts per preview batch from the most recent getPreparePreviewMesh build.
// Today these align with the native append units captured during mesh construction, and they
// provide Kotlin a stable metadata seam for staged/progressive preview delivery.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPreviewBatchTriangleCounts(
        JNIEnv* env, jobject) {
    if (g_preview_batch_triangle_counts.empty()) return nullptr;
    const auto& counts = g_preview_batch_triangle_counts;
    jintArray result = env->NewIntArray(static_cast<jsize>(counts.size()));
    if (result == nullptr) return nullptr;
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(counts.size()), counts.data());
    return result;
}

// F95: triangle index where the trailing negative/modifier-volume block begins in the cached
// preview mesh, or -1 when none. Set by getPreparePreviewMesh; lets Kotlin tag those triangles
// translucent (NativePreviewMesh.modifierBlockStartTriangle → MeshData → renderer second pass).
extern "C" JNIEXPORT jint JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetPreviewModifierBlockStart(
        JNIEnv*, jobject) {
    return static_cast<jint>(g_preview_modifier_block_start);
}

// Accessor for sapil_print.cpp to get the app files directory
std::string getFilesDir() { return g_files_dir; }

// Called by sapil_arrange.cpp when instances/scale change to invalidate cached preview
void invalidatePreviewMeshCache() {
    g_preview_mesh_valid = false;
    g_preview_volume_triangle_counts.clear();
    g_preview_batch_triangle_counts.clear();
    g_preview_modifier_block_start = -1;
}

// F66 — sync the static preview-extruder override cache when the user assigns
// a new filament slot to a volume. The preview-mesh build (line ~485) reads
// g_model_preview_extruders[objIdx][volIdx] as an override on top of
// volume->extruder_id(); if the override stays at the file-declared value while
// the volume's actual extruder changes, the slice gets the new colour but the
// Prepare preview keeps painting triangles with the file's original colour.
// Called by sapil_arrange.cpp's setVolumeExtruder.
void setPreviewExtruderOverride(int objIdx, int volIdx, int slot) {
    if (objIdx < 0 || objIdx >= (int)g_model_preview_extruders.size()) return;
    auto& vols = g_model_preview_extruders[objIdx];
    if (volIdx < 0 || volIdx >= (int)vols.size()) return;
    vols[volIdx] = slot;
}

// F66 review (review-2026-05-30 P0) — the preview-extruder override cache is
// indexed positionally by object/volume, so any operation that adds/removes
// objects or volumes must keep it in sync or the next preview build pulls
// stale overrides from a different object.
//
// Called by splitObject after `model.objects` was mutated: erase the old slot
// and insert `newCount` empty inner vectors at the same position so subsequent
// `setPreviewExtruderOverride(objIdx + k, ...)` lands on a freshly-allocated
// vector (no file-declared override carry-over).
void onSplitObjectReshape(int objIdx, int newCount, int newVolumeCount) {
    if (objIdx < 0) return;
    if (objIdx < (int)g_model_preview_extruders.size()) {
        g_model_preview_extruders.erase(g_model_preview_extruders.begin() + objIdx);
    }
    if (newCount <= 0) return;
    const std::vector<int> empty_vols(newVolumeCount > 0 ? newVolumeCount : 0, 0);
    g_model_preview_extruders.insert(
        g_model_preview_extruders.begin() + std::min(objIdx, (int)g_model_preview_extruders.size()),
        newCount, empty_vols);
}

// Called after Model::delete_object. Unlike a split, there is no replacement
// at this index: erase exactly the matching preview-extruder cache slot so all
// following object overrides retain their logical owner.
void onDeleteObjectReshape(int objIdx) {
    if (objIdx >= 0 && objIdx < (int)g_model_preview_extruders.size()) {
        g_model_preview_extruders.erase(g_model_preview_extruders.begin() + objIdx);
    }
}

// Called by splitVolume after `obj->volumes.size()` grew: resize the inner
// vector to match so subsequent setPreviewExtruderOverride(..., volIdx, ...)
// for the new volumes lands on a real slot.
void onSplitVolumeReshape(int objIdx, int newVolumeCount) {
    if (objIdx < 0 || objIdx >= (int)g_model_preview_extruders.size()) return;
    if (newVolumeCount < 0) newVolumeCount = 0;
    g_model_preview_extruders[objIdx].resize(newVolumeCount, 0);
}

// F66 review (review-2026-05-30 P0/P1) — explicit reset hooks for the
// load-time scale + rotation-base snapshots. These are positional-by-instance
// (or positional-by-object for rotation bases) and must be discarded whenever
// the object/instance enumeration shifts — splitObject, splitVolume,
// autoOrient (changes instance rotation), per-object setObjectRotation/Scale.
//
// Wrapper exists in sapil_arrange.cpp via the existing extern resetLastRotation
// + resetLoadTimeScaleFactors; this helper also drops the rotation base
// vectors so the next global setModelRotation re-snapshots against the
// current state instead of stacking on a pre-split/pre-orient baseline.
void resetRotationBases() {
    g_rotation_base_positions.clear();
    g_rotation_base_rotations.clear();
    { extern void resetLastRotation(); resetLastRotation(); }
}

void SlicerEngine::cancelPreviewMesh() {
    g_preview_cancel.store(true, std::memory_order_release);
    SAPIL_LOGI("cancelPreviewMesh: signalled cancellation");
}

void SlicerEngine::clearModel() {
    const size_t old_object_count = g_model.objects.size();
    const size_t old_preview_count = g_model_preview_extruders.size();
    g_model = Slic3r::Model();
    g_model_config = Slic3r::DynamicPrintConfig();
    g_model_info = ModelInfo();
    Slic3r::release_PlateData_list(g_plate_data_list);
    g_plate_data_list.clear();
    g_is_bbl = false;
    g_file_version = Slic3r::Semver();
    g_model_loaded = false;
    g_preview_mesh_valid = false;
    g_cached_preview_mesh = PreviewMesh();
    g_preview_batch_triangle_counts.clear();
    g_model_preview_extruders.clear();
    g_rotation_base_positions.clear();
    g_rotation_base_rotations.clear();
    { extern void resetLastRotation(); resetLastRotation(); }
    { extern void resetLoadTimeScaleFactors(); resetLoadTimeScaleFactors(); }
    g_files_dir.clear();
    std::ostringstream payload;
    payload << "{"
            << "\"oldObjectCount\":" << old_object_count << ","
            << "\"oldPreviewObjectCount\":" << old_preview_count
            << "}";
    diagnostics_record_native_event("native_model_cleared", payload.str());
    SAPIL_LOGI("Model cleared");
}

std::vector<float> SlicerEngine::setAdaptiveLayerHeight(float quality, unsigned int smoothing_radius, bool keep_min) {
    if (!g_model_loaded || g_model.objects.empty() || !std::isfinite(quality) || quality < 0.f || quality > 1.f)
        return {};

    // The loaded project config is the closest source of truth for imported files.
    // Raw models have no embedded profile, so seed just the values the slicing-parameter
    // builder needs for a conventional 0.4 mm nozzle / 0.2 mm layer baseline.
    Slic3r::DynamicPrintConfig config = g_model_config;
    if (!config.has("nozzle_diameter"))
        config.set_key_value("nozzle_diameter", new Slic3r::ConfigOptionFloats({0.4}));
    if (!config.has("filament_diameter"))
        config.set_key_value("filament_diameter", new Slic3r::ConfigOptionFloats({1.75}));
    if (!config.has("layer_height"))
        config.set_key_value("layer_height", new Slic3r::ConfigOptionFloat(0.2));
    if (!config.has("initial_layer_print_height"))
        config.set_key_value("initial_layer_print_height", new Slic3r::ConfigOptionFloat(0.2));

    float min_height = std::numeric_limits<float>::infinity();
    float max_height = 0.f;
    size_t points = 0;
    try {
        for (Slic3r::ModelObject* object : g_model.objects) {
            if (object == nullptr) continue;
            const float max_z = static_cast<float>(object->raw_bounding_box().size().z());
            const auto params = Slic3r::PrintObject::slicing_parameters(
                config, *object, max_z, Slic3r::Vec3d::Ones());
            auto profile = Slic3r::layer_height_profile_adaptive(params, *object, quality);
            profile = Slic3r::smooth_height_profile(
                profile, params, Slic3r::HeightProfileSmoothingParams(smoothing_radius, keep_min));
            for (size_t i = 1; i < profile.size(); i += 2) {
                min_height = std::min(min_height, static_cast<float>(profile[i]));
                max_height = std::max(max_height, static_cast<float>(profile[i]));
            }
            points += profile.size() / 2;
            object->layer_height_profile.set(profile);
        }
    } catch (const std::exception& e) {
        SAPIL_LOGE("Adaptive layer generation failed: %s", e.what());
        return {};
    }
    if (points == 0 || !std::isfinite(min_height)) return {};
    g_preview_mesh_valid = false;
    return {min_height, max_height, static_cast<float>(points)};
}

bool SlicerEngine::clearVariableLayerHeights() {
    if (!g_model_loaded) return false;
    for (Slic3r::ModelObject* object : g_model.objects)
        if (object != nullptr) object->layer_height_profile.clear();
    g_preview_mesh_valid = false;
    return true;
}

std::vector<float> SlicerEngine::getVariableLayerHeightRange() const {
    if (!g_model_loaded) return {};
    float min_height = std::numeric_limits<float>::infinity();
    float max_height = 0.f;
    size_t points = 0;
    for (const Slic3r::ModelObject* object : g_model.objects) {
        if (object == nullptr || object->layer_height_profile.empty()) continue;
        const auto& profile = object->layer_height_profile.get();
        for (size_t i = 1; i < profile.size(); i += 2) {
            min_height = std::min(min_height, static_cast<float>(profile[i]));
            max_height = std::max(max_height, static_cast<float>(profile[i]));
        }
        points += profile.size() / 2;
    }
    return (points == 0 || !std::isfinite(min_height))
        ? std::vector<float>() : std::vector<float>{min_height, max_height, static_cast<float>(points)};
}

bool SlicerEngine::addModel(const std::string& filepath) {
    if (!g_model_loaded) {
        SAPIL_LOGE("addModel: no primary model loaded — call loadModel first");
        return false;
    }

    std::string ext = filepath.substr(filepath.find_last_of('.') + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    if (ext != "stl" && ext != "3mf" && ext != "obj" && ext != "step" && ext != "stp") {
        SAPIL_LOGE("addModel: unsupported format: %s", ext.c_str());
        return false;
    }
    {
        std::ifstream f(filepath);
        if (!f.good()) {
            SAPIL_LOGE("addModel: file not found: %s", filepath.c_str());
            return false;
        }
    }

    try {
        Slic3r::DynamicPrintConfig tmp_config;
        Slic3r::ConfigSubstitutionContext tmp_subs(Slic3r::ForwardCompatibilitySubstitutionRule::Enable);
        Slic3r::PlateDataPtrs tmp_plates;
        std::vector<Slic3r::Preset*> tmp_presets;
        bool tmp_is_bbl = false;
        Slic3r::Semver tmp_ver;

        Slic3r::Model tmp_model = Slic3r::Model::read_from_file(
            filepath, &tmp_config, &tmp_subs,
            Slic3r::LoadStrategy::LoadModel | Slic3r::LoadStrategy::AddDefaultInstances,
            &tmp_plates, &tmp_presets, &tmp_is_bbl, &tmp_ver,
            nullptr, nullptr, nullptr, 0);

        Slic3r::release_PlateData_list(tmp_plates);

        if (tmp_model.objects.empty()) {
            SAPIL_LOGE("addModel: no objects in %s", filepath.c_str());
            return false;
        }

        for (const auto* obj : tmp_model.objects) {
            auto* new_obj = g_model.add_object(*obj);
            // Snap Z to bed plane for each added object
            if (!new_obj->instances.empty()) {
                const Slic3r::Transform3d inst_full =
                    new_obj->instances[0]->get_transformation().get_matrix();
                Slic3r::BoundingBoxf3 bb;
                for (const auto* v : new_obj->volumes) {
                    if (v->is_model_part()) {
                        bb.merge(v->mesh().transformed_bounding_box(inst_full * v->get_matrix()));
                    }
                }
                if (bb.defined && bb.min.z() != 0.0) {
                    auto off = new_obj->instances[0]->get_offset();
                    new_obj->instances[0]->set_offset(
                        Slic3r::Vec3d(off.x(), off.y(), off.z() - bb.min.z()));
                }
            }
        }

        // Rotation base and scale snapshots must be re-taken after object count changes
        g_rotation_base_positions.clear();
        g_rotation_base_rotations.clear();
        { extern void resetLastRotation(); resetLastRotation(); }
        { extern void resetLoadTimeScaleFactors(); resetLoadTimeScaleFactors(); }
        g_preview_mesh_valid = false;
        g_cached_preview_mesh = PreviewMesh();
        g_preview_batch_triangle_counts.clear();

        SAPIL_LOGI("addModel: appended %d object(s) from %s, g_model now has %d",
            (int)tmp_model.objects.size(), filepath.c_str(), (int)g_model.objects.size());
        return true;
    } catch (const std::exception& e) {
        SAPIL_LOGE("addModel: exception loading %s: %s", filepath.c_str(), e.what());
        return false;
    }
}

bool SlicerEngine::addModel(const std::string& filepath, int plate_id) {
    if (!g_model_loaded) {
        SAPIL_LOGE("addModel(plate): no primary model loaded — call loadModel first");
        return false;
    }

    std::string ext = filepath.substr(filepath.find_last_of('.') + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    if (ext != "stl" && ext != "3mf" && ext != "obj" && ext != "step" && ext != "stp") {
        SAPIL_LOGE("addModel(plate): unsupported format: %s", ext.c_str());
        return false;
    }
    {
        std::ifstream f(filepath);
        if (!f.good()) {
            SAPIL_LOGE("addModel(plate): file not found: %s", filepath.c_str());
            return false;
        }
    }

    try {
        Slic3r::DynamicPrintConfig tmp_config;
        Slic3r::ConfigSubstitutionContext tmp_subs(Slic3r::ForwardCompatibilitySubstitutionRule::Enable);
        Slic3r::PlateDataPtrs tmp_plates;
        std::vector<Slic3r::Preset*> tmp_presets;
        bool tmp_is_bbl = false;
        Slic3r::Semver tmp_ver;

        Slic3r::Model tmp_model = Slic3r::Model::read_from_file(
            filepath, &tmp_config, &tmp_subs,
            Slic3r::LoadStrategy::LoadModel | Slic3r::LoadStrategy::AddDefaultInstances,
            &tmp_plates, &tmp_presets, &tmp_is_bbl, &tmp_ver,
            nullptr, nullptr, nullptr, plate_id);

        Slic3r::release_PlateData_list(tmp_plates);

        if (tmp_model.objects.empty()) {
            SAPIL_LOGE("addModel(plate): no objects in %s (plate_id=%d)", filepath.c_str(), plate_id);
            return false;
        }

        for (const auto* obj : tmp_model.objects) {
            auto* new_obj = g_model.add_object(*obj);
            if (!new_obj->instances.empty()) {
                const Slic3r::Transform3d inst_full =
                    new_obj->instances[0]->get_transformation().get_matrix();
                Slic3r::BoundingBoxf3 bb;
                for (const auto* v : new_obj->volumes) {
                    if (v->is_model_part()) {
                        bb.merge(v->mesh().transformed_bounding_box(inst_full * v->get_matrix()));
                    }
                }
                if (bb.defined && bb.min.z() != 0.0) {
                    auto off = new_obj->instances[0]->get_offset();
                    new_obj->instances[0]->set_offset(
                        Slic3r::Vec3d(off.x(), off.y(), off.z() - bb.min.z()));
                }
            }
        }

        g_rotation_base_positions.clear();
        g_rotation_base_rotations.clear();
        { extern void resetLastRotation(); resetLastRotation(); }
        { extern void resetLoadTimeScaleFactors(); resetLoadTimeScaleFactors(); }
        g_preview_mesh_valid = false;
        g_cached_preview_mesh = PreviewMesh();
        g_preview_batch_triangle_counts.clear();

        SAPIL_LOGI("addModel(plate=%d): appended %d object(s) from %s, g_model now has %d",
            plate_id, (int)tmp_model.objects.size(), filepath.c_str(), (int)g_model.objects.size());
        return true;
    } catch (const std::exception& e) {
        SAPIL_LOGE("addModel(plate): exception loading %s: %s", filepath.c_str(), e.what());
        return false;
    }
}

std::vector<Slic3r::Vec3d>& getRotationBasePositions() {
    return g_rotation_base_positions;
}

std::vector<Slic3r::Vec3d>& getRotationBaseRotations() {
    return g_rotation_base_rotations;
}

// Accessor for the global model (used by sapil_print.cpp)
Slic3r::Model& getGlobalModel() {
    return g_model;
}

bool isModelLoaded() {
    return g_model_loaded;
}

// Config embedded in the 3MF (machine_start_gcode, change_filament_gcode, etc.)
Slic3r::DynamicPrintConfig& getModelConfig() {
    return g_model_config;
}

jobject modelInfoToJava(JNIEnv* env, const ModelInfo& info) {
    jclass cls = env->FindClass("com/u1/slicer/data/ModelInfo");
    if (!cls) {
        SAPIL_LOGE("ModelInfo class not found");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(cls, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;FFFIIZ)V");
    if (!constructor) {
        SAPIL_LOGE("ModelInfo constructor not found");
        return nullptr;
    }

    jstring jfilename = env->NewStringUTF(info.filename.c_str());
    jstring jformat = env->NewStringUTF(info.format.c_str());

    // Use NewObjectA (jvalue array) instead of NewObject (varargs) to avoid
    // C++ float→double promotion in variadic calls, which shifts subsequent
    // arguments and causes the jboolean parameter to read garbage.
    jvalue args[8];
    args[0].l = jfilename;
    args[1].l = jformat;
    args[2].f = info.size_x;
    args[3].f = info.size_y;
    args[4].f = info.size_z;
    args[5].i = info.triangle_count;
    args[6].i = info.volume_count;
    args[7].z = info.is_manifold ? JNI_TRUE : JNI_FALSE;
    jobject obj = env->NewObjectA(cls, constructor, args);

    env->DeleteLocalRef(jfilename);
    env->DeleteLocalRef(jformat);
    env->DeleteLocalRef(cls);
    return obj;
}

jobject previewMeshToJava(JNIEnv* env, const PreviewMesh& mesh) {
    jclass cls = env->FindClass("com/u1/slicer/viewer/NativePreviewMesh");
    if (!cls) {
        SAPIL_LOGE("NativePreviewMesh class not found");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(cls, "<init>", "([F[B)V");
    if (!constructor) {
        SAPIL_LOGE("NativePreviewMesh constructor not found");
        env->DeleteLocalRef(cls);
        return nullptr;
    }

    jfloatArray positions = env->NewFloatArray(static_cast<jsize>(mesh.triangle_positions.size()));
    if (!mesh.triangle_positions.empty()) {
        env->SetFloatArrayRegion(
            positions,
            0,
            static_cast<jsize>(mesh.triangle_positions.size()),
            mesh.triangle_positions.data()
        );
    }

    jbyteArray indices = env->NewByteArray(static_cast<jsize>(mesh.extruder_indices.size()));
    if (!mesh.extruder_indices.empty()) {
        std::vector<jbyte> bytes(mesh.extruder_indices.begin(), mesh.extruder_indices.end());
        env->SetByteArrayRegion(indices, 0, static_cast<jsize>(bytes.size()), bytes.data());
    }

    jobject obj = env->NewObject(cls, constructor, positions, indices);
    env->DeleteLocalRef(positions);
    env->DeleteLocalRef(indices);
    env->DeleteLocalRef(cls);
    return obj;
}

} // namespace sapil
