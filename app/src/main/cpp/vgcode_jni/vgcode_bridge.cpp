#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>
#include <cstring>

#include "../libvgcode/include/Viewer.hpp"
#include "../libvgcode/include/PathVertex.hpp"
#include "../libvgcode/include/Types.hpp"
#include "../libvgcode/include/GCodeInputData.hpp"

#define LOG_TAG "VGCodeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct VGCodeRef {
    libvgcode::Viewer viewer;
};

static inline VGCodeRef* toRef(jlong ptr) {
    return reinterpret_cast<VGCodeRef*>(ptr);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_create(JNIEnv* /*env*/, jclass /*clazz*/) {
    auto* ref = new VGCodeRef();
    LOGI("VGCodeRef created %p", ref);
    return reinterpret_cast<jlong>(ref);
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_init(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    auto* ref = toRef(ptr);
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    if (version == nullptr) version = "OpenGL ES 3.0";
    LOGI("init with GL version: %s", version);
    ref->viewer.init(std::string(version));
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_load(JNIEnv* env, jclass /*clazz*/, jlong ptr,
        jfloatArray positions, jfloatArray heights, jfloatArray widths,
        jfloatArray feedrates, jbyteArray moveTypes, jbyteArray roles,
        jbyteArray extruderIds, jintArray layerIds, jintArray toolColors) {

    auto* ref = toRef(ptr);

    jsize posLen = env->GetArrayLength(positions);
    jsize vertexCount = posLen / 3;

    jfloat* pos = env->GetFloatArrayElements(positions, nullptr);
    jfloat* h = env->GetFloatArrayElements(heights, nullptr);
    jfloat* w = env->GetFloatArrayElements(widths, nullptr);
    jfloat* fr = env->GetFloatArrayElements(feedrates, nullptr);
    jbyte* mt = env->GetByteArrayElements(moveTypes, nullptr);
    jbyte* rl = env->GetByteArrayElements(roles, nullptr);
    jbyte* eid = env->GetByteArrayElements(extruderIds, nullptr);
    jint* lid = env->GetIntArrayElements(layerIds, nullptr);

    libvgcode::GCodeInputData data;
    for (jsize i = 0; i < vertexCount; i++) {
        libvgcode::PathVertex v;
        v.position = { pos[i*3], pos[i*3+1], pos[i*3+2] };
        v.height = h[i];
        v.width = w[i];
        v.feedrate = fr[i];
        v.type = static_cast<libvgcode::EMoveType>(mt[i]);
        v.role = static_cast<libvgcode::EGCodeExtrusionRole>(rl[i]);
        v.extruder_id = static_cast<uint8_t>(eid[i]);
        v.layer_id = static_cast<uint32_t>(lid[i]);
        data.vertices.emplace_back(v);
    }

    // Tool colors
    jsize colorCount = env->GetArrayLength(toolColors);
    jint* colors = env->GetIntArrayElements(toolColors, nullptr);
    libvgcode::Palette palette;
    for (jsize i = 0; i < colorCount; i++) {
        uint32_t c = static_cast<uint32_t>(colors[i]);
        uint8_t r = (c >> 16) & 0xFF;
        uint8_t g = (c >> 8) & 0xFF;
        uint8_t b = c & 0xFF;
        palette.push_back({ r, g, b });
    }
    data.tools_colors = palette;

    env->ReleaseIntArrayElements(toolColors, colors, JNI_ABORT);
    env->ReleaseFloatArrayElements(positions, pos, JNI_ABORT);
    env->ReleaseFloatArrayElements(heights, h, JNI_ABORT);
    env->ReleaseFloatArrayElements(widths, w, JNI_ABORT);
    env->ReleaseFloatArrayElements(feedrates, fr, JNI_ABORT);
    env->ReleaseByteArrayElements(moveTypes, mt, JNI_ABORT);
    env->ReleaseByteArrayElements(roles, rl, JNI_ABORT);
    env->ReleaseByteArrayElements(extruderIds, eid, JNI_ABORT);
    env->ReleaseIntArrayElements(layerIds, lid, JNI_ABORT);

    LOGI("Loading %d vertices, %d tool colors", (int)vertexCount, (int)colorCount);
    ref->viewer.load(std::move(data));
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_render(JNIEnv* env, jclass /*clazz*/, jlong ptr,
        jfloatArray viewMatrix, jfloatArray projMatrix) {

    auto* ref = toRef(ptr);

    jfloat* vm = env->GetFloatArrayElements(viewMatrix, nullptr);
    jfloat* pm = env->GetFloatArrayElements(projMatrix, nullptr);

    libvgcode::Mat4x4 view, proj;
    std::memcpy(view.data(), vm, 16 * sizeof(float));
    std::memcpy(proj.data(), pm, 16 * sizeof(float));

    env->ReleaseFloatArrayElements(viewMatrix, vm, JNI_ABORT);
    env->ReleaseFloatArrayElements(projMatrix, pm, JNI_ABORT);

    ref->viewer.render(view, proj);
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_setLayersViewRange(JNIEnv* /*env*/, jclass /*clazz*/,
        jlong ptr, jint min, jint max) {
    auto* ref = toRef(ptr);
    ref->viewer.set_layers_view_range(static_cast<size_t>(min), static_cast<size_t>(max));
}

JNIEXPORT jlong JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_getLayersCount(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    return static_cast<jlong>(toRef(ptr)->viewer.get_layers_count());
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_setViewType(JNIEnv* /*env*/, jclass /*clazz*/,
        jlong ptr, jint type) {
    toRef(ptr)->viewer.set_view_type(static_cast<libvgcode::EViewType>(type));
}

JNIEXPORT jint JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_getViewType(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    return static_cast<jint>(toRef(ptr)->viewer.get_view_type());
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_setToolColors(JNIEnv* env, jclass /*clazz*/,
        jlong ptr, jintArray colors) {
    auto* ref = toRef(ptr);
    jsize count = env->GetArrayLength(colors);
    jint* c = env->GetIntArrayElements(colors, nullptr);

    libvgcode::Palette palette;
    for (jsize i = 0; i < count; i++) {
        uint32_t val = static_cast<uint32_t>(c[i]);
        uint8_t r = (val >> 16) & 0xFF;
        uint8_t g = (val >> 8) & 0xFF;
        uint8_t b = val & 0xFF;
        palette.push_back({ r, g, b });
    }
    env->ReleaseIntArrayElements(colors, c, JNI_ABORT);

    ref->viewer.set_tool_colors(palette);
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_toggleOptionVisibility(JNIEnv* /*env*/, jclass /*clazz*/,
        jlong ptr, jint option) {
    toRef(ptr)->viewer.toggle_option_visibility(static_cast<libvgcode::EOptionType>(option));
}

JNIEXPORT jboolean JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_isOptionVisible(JNIEnv* /*env*/, jclass /*clazz*/,
        jlong ptr, jint option) {
    return static_cast<jboolean>(
        toRef(ptr)->viewer.is_option_visible(static_cast<libvgcode::EOptionType>(option)));
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_shutdown(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    toRef(ptr)->viewer.shutdown();
}

JNIEXPORT void JNICALL
Java_com_u1_slicer_viewer_VGCodeNative_destroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
    auto* ref = toRef(ptr);
    ref->viewer.shutdown();
    delete ref;
    LOGI("VGCodeRef destroyed %p", ref);
}

} // extern "C"
