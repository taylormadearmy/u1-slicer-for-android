#include "../include/sapil.h"
#include "sapil_internal.h"
#include "sapil_diagnostics.h"
#include <fstream>
#include <algorithm>
#include <cmath>
#include <regex>
#include <chrono>

// PrusaSlicer includes
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/Format/STL.hpp"
#include "libslic3r/Format/3mf.hpp"
#include "libslic3r/Format/OBJ.hpp"
#include "libslic3r/Format/STEP.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/TriangleSelector.hpp"
#include "libslic3r/QuadricEdgeCollapse.hpp"

// miniz for direct ZIP extraction of project_settings.config
#include "miniz.h"

// =============================================================================
// sapil_model.cpp — Model loading using PrusaSlicer's Slic3r::Model
// =============================================================================

namespace sapil {

// Persistent model state
static Slic3r::Model g_model;
static Slic3r::DynamicPrintConfig g_model_config;  // Config from 3MF project_settings.config
static ModelInfo g_model_info;
static bool g_model_loaded = false;
static std::string g_files_dir;  // App files directory, derived from model path
static std::vector<std::vector<int>> g_model_preview_extruders;
static PreviewMesh g_cached_preview_mesh;
static bool g_preview_mesh_valid = false;

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
    SAPIL_LOGI("Loading model: %s", filepath.c_str());
    g_model_preview_extruders.clear();

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

        g_model = Slic3r::Model::read_from_file(filepath, &config, &config_substitutions,
            Slic3r::LoadStrategy::LoadModel | Slic3r::LoadStrategy::LoadConfig | Slic3r::LoadStrategy::AddDefaultInstances);

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

        // Calculate bounding box across all objects
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

PreviewMesh SlicerEngine::getPreparePreviewMesh(int max_triangles) const {
    PreviewMesh out;
    if (!g_model_loaded) {
        return out;
    }

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

    size_t object_index = 0;
    for (const auto* object : g_model.objects) {
        if (object == nullptr || !object->printable) continue;
        if (object->instances.empty()) continue;
        const std::vector<int>* preview_extruders =
            object_index < g_model_preview_extruders.size() ? &g_model_preview_extruders[object_index] : nullptr;

        for (const auto* instance : object->instances) {
            if (instance == nullptr || !instance->printable) continue;
            const Slic3r::Transform3d instance_matrix = instance->get_matrix();
            size_t volume_index = 0;

            for (const auto* volume : object->volumes) {
                if (volume == nullptr || !volume->is_model_part()) continue;

                int fallback_extruder = volume->extruder_id();
                if (preview_extruders != nullptr && volume_index < preview_extruders->size() &&
                    (*preview_extruders)[volume_index] > 0) {
                    fallback_extruder = (*preview_extruders)[volume_index];
                }
                if (fallback_extruder <= 0) fallback_extruder = 1;
                const uint8_t fallback_index = static_cast<uint8_t>(std::max(0, fallback_extruder - 1));

                if (!volume->mmu_segmentation_facets.empty()) {
                    std::vector<indexed_triangle_set> facets_per_type;
                    volume->mmu_segmentation_facets.get_facets(*volume, facets_per_type);
                    int tri_counter = 0;
                    for (size_t state_idx = 0; state_idx < facets_per_type.size(); ++state_idx) {
                        auto its = facets_per_type[state_idx];
                        if (its.indices.empty()) continue;
                        its_transform(its, volume->get_matrix(), true);
                        its_transform(its, instance_matrix, true);
                        const int vol_tris = static_cast<int>(its.indices.size());
                        // Same logic as non-MMU: skip small groups, rely on time budget
                        const int MIN_DECIMATION_TRIS_MMU = 1000;
                        const bool vol_needs_dec = needs_decimation && vol_tris > MIN_DECIMATION_TRIS_MMU;
                        const bool can_qem = vol_needs_dec && !qem_budget_exceeded;
                        if (can_qem) {
                            const uint32_t target = static_cast<uint32_t>(
                                std::max(INT64_C(1),
                                    static_cast<int64_t>(vol_tris) * effective_max / total_tris));
                            if (its.indices.size() > target)
                                Slic3r::its_quadric_edge_collapse(its, target);
                            if (std::chrono::steady_clock::now() > qem_deadline) {
                                qem_budget_exceeded = true;
                                SAPIL_LOGW("getPreparePreviewMesh: QEM time budget exceeded, switching to stride");
                            }
                        }
                        const uint8_t extruder_index = state_idx == 0
                            ? fallback_index
                            : static_cast<uint8_t>(state_idx - 1);
                        const int vol_stride = (can_qem || !vol_needs_dec) ? 1 : stride;
                        appendItsPreviewMesh(out, its, extruder_index, vol_stride, tri_counter);
                    }
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
                        if (its.indices.size() > target)
                            Slic3r::its_quadric_edge_collapse(its, target);
                        if (std::chrono::steady_clock::now() > qem_deadline) {
                            qem_budget_exceeded = true;
                            SAPIL_LOGW("getPreparePreviewMesh: QEM time budget exceeded, switching to stride");
                        }
                    }
                    const int vol_stride = (can_qem || !vol_needs_decimation) ? 1 : stride;
                    int tri_counter = 0;
                    appendItsPreviewMesh(out, its, fallback_index, vol_stride, tri_counter);
                }
                ++volume_index;
            }
        }
        ++object_index;
    }

    compactPreviewIndices(out);

    // Cache for instant return on tab switch
    g_cached_preview_mesh = out;
    g_preview_mesh_valid = true;

    return out;
}

// Accessor for sapil_print.cpp to get the app files directory
std::string getFilesDir() { return g_files_dir; }

// Called by sapil_arrange.cpp when instances/scale change to invalidate cached preview
void invalidatePreviewMeshCache() {
    g_preview_mesh_valid = false;
}

void SlicerEngine::clearModel() {
    const size_t old_object_count = g_model.objects.size();
    const size_t old_preview_count = g_model_preview_extruders.size();
    g_model = Slic3r::Model();
    g_model_config = Slic3r::DynamicPrintConfig();
    g_model_info = ModelInfo();
    g_model_loaded = false;
    g_preview_mesh_valid = false;
    g_cached_preview_mesh = PreviewMesh();
    g_model_preview_extruders.clear();
    g_files_dir.clear();
    std::ostringstream payload;
    payload << "{"
            << "\"oldObjectCount\":" << old_object_count << ","
            << "\"oldPreviewObjectCount\":" << old_preview_count
            << "}";
    diagnostics_record_native_event("native_model_cleared", payload.str());
    SAPIL_LOGI("Model cleared");
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
