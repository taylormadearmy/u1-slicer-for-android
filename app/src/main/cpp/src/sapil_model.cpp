#include "../include/sapil.h"
#include "sapil_internal.h"
#include <fstream>
#include <algorithm>
#include <cmath>
#include <regex>

// PrusaSlicer includes
#include "libslic3r/Model.hpp"
#include "libslic3r/TriangleMesh.hpp"
#include "libslic3r/Format/STL.hpp"
#include "libslic3r/Format/3mf.hpp"
#include "libslic3r/Format/OBJ.hpp"
#include "libslic3r/Format/STEP.hpp"
#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/TriangleSelector.hpp"

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
    uint8_t extruder_index
) {
    bool logged_invalid_index = false;
    bool logged_invalid_vertex = false;
    for (const auto& tri : its.indices) {
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

        const size_t start_size = out.triangle_positions.size();
        for (int i = 0; i < 3; ++i) {
            const auto& vertex = its.vertices[tri[i]];
            if (!std::isfinite(vertex.x()) || !std::isfinite(vertex.y()) || !std::isfinite(vertex.z())) {
                if (!logged_invalid_vertex) {
                    SAPIL_LOGW(
                        "preview triangle skipped: non-finite vertex [%.3f,%.3f,%.3f]",
                        vertex.x(), vertex.y(), vertex.z()
                    );
                    logged_invalid_vertex = true;
                }
                valid = false;
                break;
            }
            out.triangle_positions.push_back(static_cast<float>(vertex.x()));
            out.triangle_positions.push_back(static_cast<float>(vertex.y()));
            out.triangle_positions.push_back(static_cast<float>(vertex.z()));
        }
        if (!valid) {
            out.triangle_positions.resize(start_size);
            continue;
        }
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

PreviewMesh SlicerEngine::getPreparePreviewMesh() const {
    PreviewMesh out;
    if (!g_model_loaded) {
        return out;
    }

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
                    for (size_t state_idx = 0; state_idx < facets_per_type.size(); ++state_idx) {
                        auto its = facets_per_type[state_idx];
                        if (its.indices.empty()) continue;
                        its_transform(its, volume->get_matrix(), true);
                        its_transform(its, instance_matrix, true);
                        const uint8_t extruder_index = state_idx == 0
                            ? fallback_index
                            : static_cast<uint8_t>(state_idx - 1);
                        appendItsPreviewMesh(out, its, extruder_index);
                    }
                } else {
                    auto its = volume->mesh().its;
                    its_transform(its, volume->get_matrix(), true);
                    its_transform(its, instance_matrix, true);
                    appendItsPreviewMesh(out, its, fallback_index);
                }
                ++volume_index;
            }
        }
        ++object_index;
    }

    compactPreviewIndices(out);
    return out;
}

// Accessor for sapil_print.cpp to get the app files directory
std::string getFilesDir() { return g_files_dir; }

void SlicerEngine::clearModel() {
    g_model.clear_objects();
    g_model_config = Slic3r::DynamicPrintConfig();
    g_model_info = ModelInfo();
    g_model_loaded = false;
    g_model_preview_extruders.clear();
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
