// Stub implementations for OrcaSlicer features excluded from Android build
// These are referenced by libslic3r but not needed for headless FFF slicing.

// Use our Android STEP.hpp stub (provides Step class without OCCT dependency).
// Include path has extern/libslic3r_android first, so "Format/STEP.hpp" picks
// up our stub before the real OCCT-dependent header.
#include "Format/STEP.hpp"
#include <string>
#include <vector>
#include <functional>

namespace Slic3r {
class PerimeterGenerator;
namespace Arachne { struct ExtrusionLine; }
class Polygon;

// STEP — excluded (needs OCCT). Implement Step class methods declared in stub header.
Step::Step(std::string, ImportStepProgressFn, StepIsUtf8Fn) {}
bool Step::load() { return false; }

bool load_step(const char*, Model*, bool&,
               double, double, bool,
               ImportStepProgressFn, StepIsUtf8Fn,
               long&) {
    return false;
}

// FuzzySkin — excluded (needs libnoise)
namespace Feature { namespace FuzzySkin {
void apply_fuzzy_skin(Arachne::ExtrusionLine*, const PerimeterGenerator&, bool) {}
void apply_fuzzy_skin(const Polygon&, const PerimeterGenerator&, unsigned long, bool) {}
void group_region_by_fuzzify(PerimeterGenerator&) {}
} } // namespace Feature::FuzzySkin

// SVG loading — excluded (needs OCCT 7.6)
bool load_svg(const char*, Model*, std::string&) {
    return false;
}

namespace png {
// PNG writing — excluded (needs real libpng)
bool write_rgb_to_file_scaled(const std::string&, size_t, size_t,
                               const std::vector<unsigned char>&, size_t) {
    return false;
}
} // namespace png
} // namespace Slic3r
