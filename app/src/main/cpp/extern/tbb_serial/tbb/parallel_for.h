/*
    TBB Serial Shim: parallel_for.h

    Replaces TBB's parallel_for with serial execution to prevent data races
    on ARM64's weak memory ordering (SIGSEGV in ExPolygon moves during
    multi_material_segmentation_by_painting).

    Include path priority: CMake adds tbb_serial/ BEFORE real tbb/include/,
    so this file intercepts all #include <tbb/parallel_for.h>.
    Non-shimmed headers (blocked_range, partitioner, etc.) fall through to real TBB.

    IMPORTANT: Range+Body forms split the range into grain-sized chunks using
    the Range's splitting constructor, then call body on each chunk individually.
    This is necessary because some bodies use barriers or per-chunk assertions
    (e.g. name_tbb_thread_pool_threads_set_locale in Thread.cpp expects body
    to be called once per thread with a single-element range).
*/
#ifndef __TBB_parallel_for_H
#define __TBB_parallel_for_H

// Fall through to real TBB for types we need.
// partitioner.h transitively includes the real task_group.h (via relative include),
// giving us task_group_context needed by function signatures below.
#include <tbb/blocked_range.h>
#include <tbb/partitioner.h>

#include <cstddef>
#include <stdexcept>

namespace tbb {
namespace detail {
namespace d1 {

// --- Internal: split range into grain-sized chunks and call body on each ---
// Uses tail recursion: processes left half, then iterates on right half.
// Stack depth: O(log(range_size / grain_size)) — safe for any practical range.
template<typename Range, typename Body>
void serial_for_each_grain( Range range, const Body& body ) {
    while (range.is_divisible()) {
        // Split: range becomes left half, right becomes right half
        Range right(range, tbb::split());
        serial_for_each_grain(range, body);  // process left (recurse)
        range = right;  // continue with right (tail position)
    }
    body(range);  // process the final non-divisible piece
}

// --- Range + Body forms ---

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body ) {
    serial_for_each_grain(Range(range), body);
}

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, const simple_partitioner& ) {
    serial_for_each_grain(Range(range), body);
}

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, const auto_partitioner& ) {
    serial_for_each_grain(Range(range), body);
}

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, const static_partitioner& ) {
    serial_for_each_grain(Range(range), body);
}

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, affinity_partitioner& ) {
    serial_for_each_grain(Range(range), body);
}

// With task_group_context (ignored in serial mode)

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, task_group_context& ) {
    serial_for_each_grain(Range(range), body);
}

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, const simple_partitioner&, task_group_context& ) {
    serial_for_each_grain(Range(range), body);
}

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, const auto_partitioner&, task_group_context& ) {
    serial_for_each_grain(Range(range), body);
}

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, const static_partitioner&, task_group_context& ) {
    serial_for_each_grain(Range(range), body);
}

template<typename Range, typename Body>
void parallel_for( const Range& range, const Body& body, affinity_partitioner&, task_group_context& ) {
    serial_for_each_grain(Range(range), body);
}

// --- Index range forms ---
// Serial: iterate from first to last, calling f(i) for each index.

// With step
template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f ) {
    if (step <= Index(0))
        throw std::invalid_argument("Step must be positive in parallel_for");
    for (Index i = first; i < last; i = i + step)
        f(i);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, const simple_partitioner& ) {
    parallel_for(first, last, step, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, const auto_partitioner& ) {
    parallel_for(first, last, step, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, const static_partitioner& ) {
    parallel_for(first, last, step, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, affinity_partitioner& ) {
    parallel_for(first, last, step, f);
}

// Without step (default step = 1)
template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f ) {
    for (Index i = first; i < last; ++i)
        f(i);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, const simple_partitioner& ) {
    parallel_for(first, last, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, const auto_partitioner& ) {
    parallel_for(first, last, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, const static_partitioner& ) {
    parallel_for(first, last, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, affinity_partitioner& ) {
    parallel_for(first, last, f);
}

// With step + context
template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, task_group_context& ) {
    parallel_for(first, last, step, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, const simple_partitioner&, task_group_context& ) {
    parallel_for(first, last, step, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, const auto_partitioner&, task_group_context& ) {
    parallel_for(first, last, step, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, const static_partitioner&, task_group_context& ) {
    parallel_for(first, last, step, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, Index step, const Function& f, affinity_partitioner&, task_group_context& ) {
    parallel_for(first, last, step, f);
}

// Without step + context
template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, task_group_context& ) {
    parallel_for(first, last, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, const simple_partitioner&, task_group_context& ) {
    parallel_for(first, last, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, const auto_partitioner&, task_group_context& ) {
    parallel_for(first, last, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, const static_partitioner&, task_group_context& ) {
    parallel_for(first, last, f);
}

template<typename Index, typename Function>
void parallel_for( Index first, Index last, const Function& f, affinity_partitioner&, task_group_context& ) {
    parallel_for(first, last, f);
}

} // namespace d1
} // namespace detail

inline namespace v1 {
using detail::d1::parallel_for;
// Split types (needed by callers)
using detail::split;
using detail::proportional_split;
} // namespace v1

} // namespace tbb

#endif /* __TBB_parallel_for_H */
