/*
    TBB Serial Shim: parallel_invoke.h

    Replaces TBB's parallel_invoke with sequential execution.
    All callables are invoked one after another.
*/
#ifndef __TBB_parallel_invoke_H
#define __TBB_parallel_invoke_H

// partitioner.h transitively includes the real task_group.h (via relative include)
#include <tbb/partitioner.h>

#include <utility>

namespace tbb {
namespace detail {
namespace d1 {

// Variadic: call all functions sequentially.
// The real TBB API allows the last argument to be a task_group_context&.
// We handle both cases via overload resolution.

// Base case: 2+ callables, no context
template<typename F1, typename F2>
void parallel_invoke(const F1& f1, const F2& f2) {
    f1();
    f2();
}

template<typename F1, typename F2, typename F3>
void parallel_invoke(const F1& f1, const F2& f2, const F3& f3) {
    f1();
    f2();
    f3();
}

template<typename F1, typename F2, typename F3, typename F4>
void parallel_invoke(const F1& f1, const F2& f2, const F3& f3, const F4& f4) {
    f1();
    f2();
    f3();
    f4();
}

template<typename F1, typename F2, typename F3, typename F4, typename F5>
void parallel_invoke(const F1& f1, const F2& f2, const F3& f3, const F4& f4, const F5& f5) {
    f1();
    f2();
    f3();
    f4();
    f5();
}

template<typename F1, typename F2, typename F3, typename F4, typename F5, typename F6>
void parallel_invoke(const F1& f1, const F2& f2, const F3& f3, const F4& f4, const F5& f5, const F6& f6) {
    f1();
    f2();
    f3();
    f4();
    f5();
    f6();
}

template<typename F1, typename F2, typename F3, typename F4, typename F5, typename F6, typename F7>
void parallel_invoke(const F1& f1, const F2& f2, const F3& f3, const F4& f4, const F5& f5, const F6& f6, const F7& f7) {
    f1();
    f2();
    f3();
    f4();
    f5();
    f6();
    f7();
}

template<typename F1, typename F2, typename F3, typename F4, typename F5, typename F6, typename F7, typename F8>
void parallel_invoke(const F1& f1, const F2& f2, const F3& f3, const F4& f4, const F5& f5, const F6& f6, const F7& f7, const F8& f8) {
    f1();
    f2();
    f3();
    f4();
    f5();
    f6();
    f7();
    f8();
}

template<typename F1, typename F2, typename F3, typename F4, typename F5, typename F6, typename F7, typename F8, typename F9>
void parallel_invoke(const F1& f1, const F2& f2, const F3& f3, const F4& f4, const F5& f5, const F6& f6, const F7& f7, const F8& f8, const F9& f9) {
    f1();
    f2();
    f3();
    f4();
    f5();
    f6();
    f7();
    f8();
    f9();
}

template<typename F1, typename F2, typename F3, typename F4, typename F5, typename F6, typename F7, typename F8, typename F9, typename F10>
void parallel_invoke(const F1& f1, const F2& f2, const F3& f3, const F4& f4, const F5& f5, const F6& f6, const F7& f7, const F8& f8, const F9& f9, const F10& f10) {
    f1();
    f2();
    f3();
    f4();
    f5();
    f6();
    f7();
    f8();
    f9();
    f10();
}

// With task_group_context as first argument (context ignored in serial mode)
template<typename F1, typename F2>
void parallel_invoke(task_group_context&, const F1& f1, const F2& f2) {
    f1();
    f2();
}

template<typename F1, typename F2, typename F3>
void parallel_invoke(task_group_context&, const F1& f1, const F2& f2, const F3& f3) {
    f1();
    f2();
    f3();
}

} // namespace d1
} // namespace detail

inline namespace v1 {
using detail::d1::parallel_invoke;
} // namespace v1

} // namespace tbb

#endif /* __TBB_parallel_invoke_H */
