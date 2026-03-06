// Minimal boost::locale stub for Android.
// On Android, we don't link the real libboost_locale (it requires ICU or iconv).
// Instead, we stub the handful of symbols that libslic3r references.
//
// normalize_utf8_nfc() uses boost::locale::generator + boost::locale::normalize.
// Since Android filesystems already store UTF-8 in NFC, returning the string
// unchanged is correct for all practical purposes.

#include <boost/locale/conversion.hpp>
#include <boost/locale/detail/facet_id.hpp>
#include <boost/locale/generator.hpp>
#include <locale>
#include <string>

// Required explicit instantiation for the converter facet
template<>
std::locale::id boost::locale::detail::facet_id<boost::locale::converter<char>>::id = {};

// Stub: boost::locale::generator — used by normalize_utf8_nfc() in utils.cpp
// Returns the global "C" locale; no backend registration needed on Android.
namespace boost {
namespace locale {

generator::generator() {}
generator::~generator() {}

std::locale generator::generate(const std::string& /*id*/) const {
    return std::locale::classic();
}

} // namespace locale
} // namespace boost
