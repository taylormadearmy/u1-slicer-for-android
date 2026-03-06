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
