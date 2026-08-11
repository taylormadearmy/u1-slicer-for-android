/*
    oneAPI spelling of the Android serial parallel_for shim.

    OrcaSlicer's PrintObject.cpp includes <oneapi/tbb/parallel_for.h> before
    <tbb/parallel_for.h>.  Without this forwarding header the real oneTBB
    implementation wins and the later legacy include is suppressed by TBB's
    shared include guard.  That leaves perimeter generation parallel on ARM64
    and has produced repeatable invalid frees in MultiPoint::~MultiPoint().

    CMake places extern/tbb_serial before the real TBB include directory, so
    forward both include spellings to the single serial implementation.
*/
#pragma once

#include <tbb/parallel_for.h>
