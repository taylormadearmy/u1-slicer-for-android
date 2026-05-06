#include "../include/sapil.h"
#include <sstream>

// =============================================================================
// sapil_config.cpp — SliceConfig JNI conversion
// =============================================================================
// Converts between Java SliceConfig objects and C++ sapil::SliceConfig structs.
// When PrusaSlicer is integrated, this will also map to Slic3r::DynamicPrintConfig.
// =============================================================================

namespace sapil {

SliceConfig configFromJava(JNIEnv* env, jobject jconfig) {
    SliceConfig config;

    if (!jconfig) return config;

    jclass cls = env->GetObjectClass(jconfig);
    if (!cls) return config;

    // Helper lambdas
    auto getFloat = [&](const char* name) -> float {
        jfieldID fid = env->GetFieldID(cls, name, "F");
        return fid ? env->GetFloatField(jconfig, fid) : 0.0f;
    };
    auto getInt = [&](const char* name) -> int {
        jfieldID fid = env->GetFieldID(cls, name, "I");
        return fid ? env->GetIntField(jconfig, fid) : 0;
    };
    auto getBool = [&](const char* name) -> bool {
        jfieldID fid = env->GetFieldID(cls, name, "Z");
        return fid ? env->GetBooleanField(jconfig, fid) : false;
    };
    auto getString = [&](const char* name) -> std::string {
        jfieldID fid = env->GetFieldID(cls, name, "Ljava/lang/String;");
        if (!fid) return "";
        auto jstr = (jstring) env->GetObjectField(jconfig, fid);
        if (!jstr) return "";
        const char* chars = env->GetStringUTFChars(jstr, nullptr);
        std::string result(chars);
        env->ReleaseStringUTFChars(jstr, chars);
        return result;
    };
    auto getIntArray = [&](const char* name) -> std::vector<int> {
        jfieldID fid = env->GetFieldID(cls, name, "[I");
        if (!fid) return {};
        auto jarr = (jintArray) env->GetObjectField(jconfig, fid);
        if (!jarr) return {};
        jsize len = env->GetArrayLength(jarr);
        std::vector<int> result(len);
        env->GetIntArrayRegion(jarr, 0, len, result.data());
        env->DeleteLocalRef(jarr);
        return result;
    };
    auto getFloatArray = [&](const char* name) -> std::vector<float> {
        jfieldID fid = env->GetFieldID(cls, name, "[F");
        if (!fid) return {};
        auto jarr = (jfloatArray) env->GetObjectField(jconfig, fid);
        if (!jarr) return {};
        jsize len = env->GetArrayLength(jarr);
        std::vector<float> result(len);
        env->GetFloatArrayRegion(jarr, 0, len, result.data());
        env->DeleteLocalRef(jarr);
        return result;
    };
    auto getStringArray = [&](const char* name) -> std::vector<std::string> {
        jfieldID fid = env->GetFieldID(cls, name, "[Ljava/lang/String;");
        if (!fid) return {};
        auto jarr = (jobjectArray) env->GetObjectField(jconfig, fid);
        if (!jarr) return {};
        jsize len = env->GetArrayLength(jarr);
        std::vector<std::string> result;
        result.reserve(len);
        for (jsize i = 0; i < len; ++i) {
            auto jstr = (jstring) env->GetObjectArrayElement(jarr, i);
            if (jstr) {
                const char* chars = env->GetStringUTFChars(jstr, nullptr);
                result.emplace_back(chars ? chars : "");
                env->ReleaseStringUTFChars(jstr, chars);
                env->DeleteLocalRef(jstr);
            } else {
                result.emplace_back("");
            }
        }
        env->DeleteLocalRef(jarr);
        return result;
    };

    // Map fields
    config.layer_height = getFloat("layerHeight");
    config.first_layer_height = getFloat("firstLayerHeight");
    config.perimeters = getInt("perimeters");
    config.top_solid_layers = getInt("topSolidLayers");
    config.bottom_solid_layers = getInt("bottomSolidLayers");
    config.fill_density = getFloat("fillDensity");
    config.fill_pattern = getString("fillPattern");

    config.print_speed = getFloat("printSpeed");
    config.travel_speed = getFloat("travelSpeed");
    config.first_layer_speed = getFloat("firstLayerSpeed");

    config.nozzle_temp = getInt("nozzleTemp");
    config.bed_temp = getInt("bedTemp");

    config.retract_length = getFloat("retractLength");
    config.retract_speed = getFloat("retractSpeed");

    config.support_enabled = getBool("supportEnabled");
    config.support_type = getString("supportType");
    config.support_angle = getFloat("supportAngle");
    config.support_filament = getInt("supportFilament");
    config.support_interface_filament = getInt("supportInterfaceFilament");

    config.skirt_loops = getInt("skirtLoops");
    config.skirt_distance = getFloat("skirtDistance");
    config.brim_width = getFloat("brimWidth");

    config.bed_size_x = getFloat("bedSizeX");
    config.bed_size_y = getFloat("bedSizeY");
    config.max_print_height = getFloat("maxPrintHeight");

    config.nozzle_diameter = getFloat("nozzleDiameter");
    config.filament_diameter = getFloat("filamentDiameter");
    config.filament_type = getString("filamentType");
    config.filament_types = getStringArray("filamentTypes");

    // Multi-extruder
    config.extruder_count = getInt("extruderCount");
    config.extruder_temps = getIntArray("extruderTemps");
    config.extruder_retract_length = getFloatArray("extruderRetractLength");
    config.extruder_retract_speed = getFloatArray("extruderRetractSpeed");
    config.wipe_tower_enabled = getBool("wipeTowerEnabled");
    config.wipe_tower_x = getFloat("wipeTowerX");
    config.wipe_tower_y = getFloat("wipeTowerY");
    config.wipe_tower_width = getFloat("wipeTowerWidth");

    // B106: machine G-code templates for STL files (no embedded Snapmaker profile).
    config.machine_start_gcode = getString("machineStartGcode");
    config.machine_end_gcode = getString("machineEndGcode");

    env->DeleteLocalRef(cls);
    return config;
}

jobject configToJava(JNIEnv* env, const SliceConfig& config) {
    jclass cls = env->FindClass("com/u1/slicer/data/SliceConfig");
    if (!cls) {
        SAPIL_LOGE("SliceConfig class not found");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(cls, "<init>", "()V");
    if (!constructor) {
        SAPIL_LOGE("SliceConfig constructor not found");
        return nullptr;
    }

    jobject obj = env->NewObject(cls, constructor);

    auto setFloat = [&](const char* name, float val) {
        jfieldID fid = env->GetFieldID(cls, name, "F");
        if (fid) env->SetFloatField(obj, fid, val);
    };
    auto setInt = [&](const char* name, int val) {
        jfieldID fid = env->GetFieldID(cls, name, "I");
        if (fid) env->SetIntField(obj, fid, val);
    };
    auto setBool = [&](const char* name, bool val) {
        jfieldID fid = env->GetFieldID(cls, name, "Z");
        if (fid) env->SetBooleanField(obj, fid, val);
    };
    auto setString = [&](const char* name, const std::string& val) {
        jfieldID fid = env->GetFieldID(cls, name, "Ljava/lang/String;");
        if (fid) {
            jstring jval = env->NewStringUTF(val.c_str());
            env->SetObjectField(obj, fid, jval);
            env->DeleteLocalRef(jval);
        }
    };
    auto setIntArray = [&](const char* name, const std::vector<int>& vals) {
        jfieldID fid = env->GetFieldID(cls, name, "[I");
        if (fid && !vals.empty()) {
            jintArray jarr = env->NewIntArray(vals.size());
            env->SetIntArrayRegion(jarr, 0, vals.size(), vals.data());
            env->SetObjectField(obj, fid, jarr);
            env->DeleteLocalRef(jarr);
        }
    };
    auto setFloatArray = [&](const char* name, const std::vector<float>& vals) {
        jfieldID fid = env->GetFieldID(cls, name, "[F");
        if (fid && !vals.empty()) {
            jfloatArray jarr = env->NewFloatArray(vals.size());
            env->SetFloatArrayRegion(jarr, 0, vals.size(), vals.data());
            env->SetObjectField(obj, fid, jarr);
            env->DeleteLocalRef(jarr);
        }
    };
    auto setStringArray = [&](const char* name, const std::vector<std::string>& vals) {
        jfieldID fid = env->GetFieldID(cls, name, "[Ljava/lang/String;");
        if (fid && !vals.empty()) {
            jclass stringCls = env->FindClass("java/lang/String");
            jobjectArray jarr = env->NewObjectArray(vals.size(), stringCls, nullptr);
            for (jsize i = 0; i < (jsize) vals.size(); ++i) {
                jstring jval = env->NewStringUTF(vals[i].c_str());
                env->SetObjectArrayElement(jarr, i, jval);
                env->DeleteLocalRef(jval);
            }
            env->SetObjectField(obj, fid, jarr);
            env->DeleteLocalRef(jarr);
            env->DeleteLocalRef(stringCls);
        }
    };

    setFloat("layerHeight", config.layer_height);
    setFloat("firstLayerHeight", config.first_layer_height);
    setInt("perimeters", config.perimeters);
    setInt("topSolidLayers", config.top_solid_layers);
    setInt("bottomSolidLayers", config.bottom_solid_layers);
    setFloat("fillDensity", config.fill_density);
    setString("fillPattern", config.fill_pattern);
    setFloat("printSpeed", config.print_speed);
    setFloat("travelSpeed", config.travel_speed);
    setFloat("firstLayerSpeed", config.first_layer_speed);
    setInt("nozzleTemp", config.nozzle_temp);
    setInt("bedTemp", config.bed_temp);
    setFloat("retractLength", config.retract_length);
    setFloat("retractSpeed", config.retract_speed);
    setBool("supportEnabled", config.support_enabled);
    setString("supportType", config.support_type);
    setFloat("supportAngle", config.support_angle);
    setInt("supportFilament", config.support_filament);
    setInt("supportInterfaceFilament", config.support_interface_filament);
    setInt("skirtLoops", config.skirt_loops);
    setFloat("skirtDistance", config.skirt_distance);
    setFloat("brimWidth", config.brim_width);
    setFloat("bedSizeX", config.bed_size_x);
    setFloat("bedSizeY", config.bed_size_y);
    setFloat("maxPrintHeight", config.max_print_height);
    setFloat("nozzleDiameter", config.nozzle_diameter);
    setFloat("filamentDiameter", config.filament_diameter);
    setString("filamentType", config.filament_type);
    setStringArray("filamentTypes", config.filament_types);

    // Multi-extruder
    setInt("extruderCount", config.extruder_count);
    setIntArray("extruderTemps", config.extruder_temps);
    setFloatArray("extruderRetractLength", config.extruder_retract_length);
    setFloatArray("extruderRetractSpeed", config.extruder_retract_speed);
    setBool("wipeTowerEnabled", config.wipe_tower_enabled);
    setFloat("wipeTowerX", config.wipe_tower_x);
    setFloat("wipeTowerY", config.wipe_tower_y);
    setFloat("wipeTowerWidth", config.wipe_tower_width);
    setString("machineStartGcode", config.machine_start_gcode);
    setString("machineEndGcode", config.machine_end_gcode);

    env->DeleteLocalRef(cls);
    return obj;
}

} // namespace sapil
