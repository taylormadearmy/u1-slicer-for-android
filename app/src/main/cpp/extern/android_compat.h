// Android build compatibility header for OrcaSlicer
// Force-included via -include to fix missing standard includes and TBB compat
#pragma once

// LocalesUtils.cpp needs sstream, libnest2d needs iostream
#include <sstream>
#include <iostream>

// TBB compatibility: OrcaSlicer uses tbb::spin_mutex which is in
// tbb::detail::d1 in oneTBB 2021.x
#include <tbb/spin_mutex.h>
namespace tbb {
    using spin_mutex = detail::d1::spin_mutex;
}

// Override Format/STEP.hpp before any source file processes it.
// The orca source Format/STEP.hpp includes OCCT headers which are not available
// on Android. The android stub provides OCCT-free declarations.
// Since this header is force-included before every .cpp, the guard
// slic3r_Format_STEP_hpp_ will already be set when Model.hpp includes
// "Format/STEP.hpp", causing the real OCCT-dependent header to be skipped.
#ifdef __cplusplus
#include "libslic3r_android/Format/STEP.hpp"
#endif
