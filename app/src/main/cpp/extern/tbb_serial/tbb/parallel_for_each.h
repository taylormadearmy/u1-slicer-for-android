/*
    TBB Serial Shim: parallel_for_each.h

    Replaces TBB's parallel_for_each with a sequential for loop.
    The feeder class is stubbed (add() is a no-op since serial execution
    doesn't need work-stealing).
*/
#ifndef __TBB_parallel_for_each_H
#define __TBB_parallel_for_each_H

// partitioner.h transitively includes the real task_group.h (via relative include)
#include <tbb/partitioner.h>

#include <iterator>
#include <type_traits>
#include <vector>

namespace tbb {
namespace detail {
namespace d1 {

// Stub feeder — add() queues items for processing after current iteration
template<typename Item>
class feeder {
    std::vector<Item> m_items;
    feeder(const feeder&) = delete;
    void operator=(const feeder&) = delete;
public:
    feeder() = default;
    void add(const Item& item) { m_items.push_back(item); }
    void add(Item&& item) { m_items.push_back(std::move(item)); }

    // Internal: drain queued items
    bool has_items() const { return !m_items.empty(); }
    std::vector<Item>& items() { return m_items; }
};

namespace serial_detail {

// SFINAE: detect if body accepts a feeder argument
template<typename Body, typename Item, typename = void>
struct body_uses_feeder : std::false_type {};

template<typename Body, typename Item>
struct body_uses_feeder<Body, Item,
    decltype(std::declval<const Body&>()(std::declval<Item>(), std::declval<feeder<typename std::decay<Item>::type>&>()), void())>
    : std::true_type {};

// Call body without feeder
template<typename Body, typename Item>
typename std::enable_if<!body_uses_feeder<Body, Item>::value>::type
invoke_body(const Body& body, Item&& item, feeder<typename std::decay<Item>::type>&) {
    body(std::forward<Item>(item));
}

// Call body with feeder
template<typename Body, typename Item>
typename std::enable_if<body_uses_feeder<Body, Item>::value>::type
invoke_body(const Body& body, Item&& item, feeder<typename std::decay<Item>::type>& f) {
    body(std::forward<Item>(item), f);
}

} // namespace serial_detail

} // namespace d1

namespace d2 {

template<typename Iterator, typename Body>
void parallel_for_each(Iterator first, Iterator last, const Body& body) {
    using Item = typename std::iterator_traits<Iterator>::value_type;
    d1::feeder<Item> f;
    for (Iterator it = first; it != last; ++it) {
        d1::serial_detail::invoke_body(body, *it, f);
    }
    // Process any items added via feeder
    while (f.has_items()) {
        std::vector<Item> items;
        std::swap(items, f.items());
        for (auto& item : items) {
            d1::serial_detail::invoke_body(body, std::move(item), f);
        }
    }
}

template<typename Range, typename Body>
void parallel_for_each(Range& rng, const Body& body) {
    parallel_for_each(std::begin(rng), std::end(rng), body);
}

template<typename Range, typename Body>
void parallel_for_each(const Range& rng, const Body& body) {
    parallel_for_each(std::begin(rng), std::end(rng), body);
}

// With context (ignored in serial mode)
template<typename Iterator, typename Body>
void parallel_for_each(Iterator first, Iterator last, const Body& body, d1::task_group_context&) {
    parallel_for_each(first, last, body);
}

template<typename Range, typename Body>
void parallel_for_each(Range& rng, const Body& body, d1::task_group_context&) {
    parallel_for_each(std::begin(rng), std::end(rng), body);
}

template<typename Range, typename Body>
void parallel_for_each(const Range& rng, const Body& body, d1::task_group_context&) {
    parallel_for_each(std::begin(rng), std::end(rng), body);
}

} // namespace d2

} // namespace detail

inline namespace v1 {
using detail::d2::parallel_for_each;
using detail::d1::feeder;
} // namespace v1

} // namespace tbb

#endif /* __TBB_parallel_for_each_H */
