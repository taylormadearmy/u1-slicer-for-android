// Stub implementations for boost::locale symbols not available on Android
// These provide just enough to link — actual locale functionality returns std::locale()

// Include the real boost headers so we match their class layout exactly
#include <boost/locale/generator.hpp>
#include <boost/locale/conversion.hpp>
#include <boost/locale/detail/facet_id.hpp>

#include <locale>
#include <string>

// The generator class is declared in boost/locale/generator.hpp with
// non-inline constructor/destructor. We provide stub implementations.
namespace boost {
namespace locale {

generator::generator() {}
generator::~generator() {}

std::locale generator::generate(const std::string&) const {
    return std::locale();
}

} // namespace locale
} // namespace boost

// Explicit definition of the static id member for facet_id<converter<char>>
// This must be at namespace scope, not inside the class
template<>
std::locale::id boost::locale::detail::facet_id<boost::locale::converter<char>>::id = {};
