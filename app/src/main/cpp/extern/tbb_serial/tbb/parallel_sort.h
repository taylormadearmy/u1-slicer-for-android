/*
    TBB Serial Shim: parallel_sort.h

    Replaces TBB's parallel_sort with std::sort.
*/
#ifndef __TBB_parallel_sort_H
#define __TBB_parallel_sort_H

#include <algorithm>
#include <iterator>
#include <functional>

namespace tbb {
namespace detail {
namespace d1 {

template<typename RandomAccessIterator, typename Compare>
void parallel_sort( RandomAccessIterator begin, RandomAccessIterator end, const Compare& comp ) {
    std::sort(begin, end, comp);
}

template<typename RandomAccessIterator>
void parallel_sort( RandomAccessIterator begin, RandomAccessIterator end ) {
    std::sort(begin, end);
}

template<typename Range, typename Compare>
void parallel_sort( Range&& rng, const Compare& comp ) {
    std::sort(std::begin(rng), std::end(rng), comp);
}

template<typename Range>
void parallel_sort( Range&& rng ) {
    std::sort(std::begin(rng), std::end(rng));
}

} // namespace d1
} // namespace detail

inline namespace v1 {
using detail::d1::parallel_sort;
} // namespace v1
} // namespace tbb

#endif /* __TBB_parallel_sort_H */
