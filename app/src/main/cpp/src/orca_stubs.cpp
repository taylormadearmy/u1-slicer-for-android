// Stub implementations for OrcaSlicer features excluded from Android build
// These are referenced by libslic3r but not needed for headless FFF slicing.

#include <string>
#include <vector>
#include <functional>
#include <stdexcept>

namespace Slic3r {
class Model;

// STEP loading — excluded (needs OCCT 7.6 inline implementations)
bool load_step(const char*, Model*, bool&,
               std::function<void(int, int, int, bool&)>,
               std::function<void(bool)>) {
    return false;
}

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
