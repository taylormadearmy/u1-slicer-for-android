/*
    TBB Serial Shim: parallel_reduce.h

    Replaces TBB's parallel_reduce/parallel_deterministic_reduce with serial execution.
    Body form: call body(range) directly (no splitting, no join needed).
    Lambda form: call real_body(range, identity) directly (no reduction step needed).
*/
#ifndef __TBB_parallel_reduce_H
#define __TBB_parallel_reduce_H

// partitioner.h transitively includes the real task_group.h (via relative include)
#include <tbb/blocked_range.h>
#include <tbb/partitioner.h>

namespace tbb {
namespace detail {
namespace d1 {

// ============================================================
// Body form: void parallel_reduce(range, body [, partitioner] [, context])
// Serial: just call body(range). No splitting, so no join() needed.
// ============================================================

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, const simple_partitioner& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, const auto_partitioner& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, const static_partitioner& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, affinity_partitioner& ) {
    body(const_cast<Range&>(range));
}

// With context
template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, task_group_context& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, const simple_partitioner&, task_group_context& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, const auto_partitioner&, task_group_context& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, const static_partitioner&, task_group_context& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_reduce( const Range& range, Body& body, affinity_partitioner&, task_group_context& ) {
    body(const_cast<Range&>(range));
}

// ============================================================
// Lambda form: Value parallel_reduce(range, identity, real_body, reduction [, partitioner] [, context])
// Serial: return real_body(range, identity). No reduction step needed with single pass.
// ============================================================

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const simple_partitioner& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const auto_partitioner& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const static_partitioner& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, affinity_partitioner& ) {
    return real_body(range, identity);
}

// With context
template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, task_group_context& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const simple_partitioner&, task_group_context& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const auto_partitioner&, task_group_context& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const static_partitioner&, task_group_context& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, affinity_partitioner&, task_group_context& ) {
    return real_body(range, identity);
}

// ============================================================
// parallel_deterministic_reduce — same API, same serial behavior
// ============================================================

// Body form
template<typename Range, typename Body>
void parallel_deterministic_reduce( const Range& range, Body& body ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_deterministic_reduce( const Range& range, Body& body, const simple_partitioner& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_deterministic_reduce( const Range& range, Body& body, const static_partitioner& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_deterministic_reduce( const Range& range, Body& body, task_group_context& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_deterministic_reduce( const Range& range, Body& body, const simple_partitioner&, task_group_context& ) {
    body(const_cast<Range&>(range));
}

template<typename Range, typename Body>
void parallel_deterministic_reduce( const Range& range, Body& body, const static_partitioner&, task_group_context& ) {
    body(const_cast<Range&>(range));
}

// Lambda form
template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_deterministic_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_deterministic_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const simple_partitioner& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_deterministic_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const static_partitioner& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_deterministic_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, task_group_context& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_deterministic_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const simple_partitioner&, task_group_context& ) {
    return real_body(range, identity);
}

template<typename Range, typename Value, typename RealBody, typename Reduction>
Value parallel_deterministic_reduce( const Range& range, const Value& identity, const RealBody& real_body, const Reduction&, const static_partitioner&, task_group_context& ) {
    return real_body(range, identity);
}

} // namespace d1
} // namespace detail

inline namespace v1 {
using detail::d1::parallel_reduce;
using detail::d1::parallel_deterministic_reduce;
using detail::split;
using detail::proportional_split;
} // namespace v1

} // namespace tbb

#endif /* __TBB_parallel_reduce_H */
