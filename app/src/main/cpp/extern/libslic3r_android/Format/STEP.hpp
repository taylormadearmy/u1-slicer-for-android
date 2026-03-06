// Android stub for Format/STEP.hpp — replaces the real header which requires OCCT.
// STEP loading is excluded from the Android build (needs OCCT 7.x native libs).
#ifndef slic3r_Format_STEP_hpp_
#define slic3r_Format_STEP_hpp_

#include <string>
#include <functional>
#include <atomic>

namespace Slic3r {

class Model;

const int LOAD_STEP_STAGE_READ_FILE  = 0;
const int LOAD_STEP_STAGE_GET_SOLID  = 1;
const int LOAD_STEP_STAGE_GET_MESH   = 2;
const int LOAD_STEP_STAGE_NUM        = 3;
const int LOAD_STEP_STAGE_UNIT_NUM   = 5;

typedef std::function<void(int load_stage, int current, int total, bool& cancel)> ImportStepProgressFn;
typedef std::function<void(bool isUtf8)> StepIsUtf8Fn;

bool load_step(const char* path, Model* model,
               bool& is_cancel,
               double linear_defletion = 0.003,
               double angle_defletion = 0.5,
               bool isSplitCompound = false,
               ImportStepProgressFn proFn = nullptr,
               StepIsUtf8Fn isUtf8Fn = nullptr,
               long& mesh_face_num = *(new long(-1)));

class StepPreProcessor {
public:
    bool preprocess(const char* path, std::string& output_path) { return false; }
    static bool isUtf8File(const char* path) { return true; }
    static bool isUtf8(const std::string str) { return true; }
};

class Step {
public:
    Step(std::string path, ImportStepProgressFn stepFn = nullptr, StepIsUtf8Fn isUtf8Fn = nullptr);
    bool load();
    unsigned int get_triangle_num(double, double) { return 0; }
    unsigned int get_triangle_num_tbb(double, double) { return 0; }
    void clean_mesh_data() {}
    std::atomic<bool> m_stop_mesh{false};
};

} // namespace Slic3r

#endif /* slic3r_Format_STEP_hpp_ */
