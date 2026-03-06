// TBB compatibility shim for OrcaSlicer on oneTBB 2021.x
// OrcaSlicer uses tbb::spin_mutex which lives in tbb::detail::d1 in oneTBB
#pragma once
#include <tbb/spin_mutex.h>
namespace tbb {
    using spin_mutex = detail::d1::spin_mutex;
}
