// sapil_bambu_objects.cpp
//
// Phase 1 sub-plan #4: full object list JNI accessor. Pure read of
// g_model.objects; reuses sapil::append_object (promoted from the
// sapil_bambu_snapshot.cpp anonymous namespace). Callers hold
// NativeLibrary.previewMutex on the Kotlin side; C++ assumes serialised access.

#include <jni.h>

#include <sstream>
#include <string>

#include "libslic3r/Model.hpp"

#include "sapil_bambu_snapshot.h"  // sapil::append_object

namespace sapil {
extern Slic3r::Model g_model;
} // namespace sapil

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_u1_slicer_NativeLibrary_nativeGetObjectExtruderMap(
        JNIEnv* env, jobject) {
    if (sapil::g_model.objects.empty()) return nullptr;
    std::ostringstream out;
    out << "[";
    for (size_t i = 0; i < sapil::g_model.objects.size(); ++i) {
        if (i) out << ",";
        const Slic3r::ModelObject* mo = sapil::g_model.objects[i];
        if (mo == nullptr) {
            out << "null";
            continue;
        }
        sapil::append_object(out, *mo);
    }
    out << "]";
    return env->NewStringUTF(out.str().c_str());
}

} // extern "C"
