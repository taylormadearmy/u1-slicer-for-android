// Minimal boost::locale glue for Android
// The real libboost_locale is linked, but facet_id needs explicit instantiation
#include <boost/locale/conversion.hpp>
#include <boost/locale/detail/facet_id.hpp>
#include <locale>

template<>
std::locale::id boost::locale::detail::facet_id<boost::locale::converter<char>>::id = {};
