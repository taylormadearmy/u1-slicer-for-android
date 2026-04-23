// sapil_bambu_snapshot.cpp
//
// Phase 0 differential harness: walks the global Slic3r::Model after
// Model::read_from_file completes, emitting a JSON shaped exactly like
// the Kotlin BambuFileSnapshot. Compared against the Kotlin parser path
// to surface drift.
//
// Initial commit only emits header fields (source, isBbl, fileVersion,
// empty plates/objects/volumes arrays). Subsequent commits expand to
// per-plate, per-object, per-volume sections.

#include "sapil_bambu_snapshot.h"

#include <sstream>
#include <string>

#include "libslic3r/Model.hpp"

namespace sapil {

// Provided by sapil_model.cpp (non-static, file-scope externally linkable).
extern Slic3r::Model g_model;

// ModelInfo is defined in sapil.h within namespace sapil.
// g_model_info.filename is populated by SlicerEngine::loadModel.
extern ModelInfo g_model_info;

namespace {

std::string json_escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"':  out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:   out += c;       break;
        }
    }
    return out;
}

} // namespace

std::string bambu_snapshot_json() {
    if (g_model.objects.empty()) return "";

    std::ostringstream out;
    out << "{";
    out << "\"source\":\"" << json_escape(g_model_info.filename) << "\",";
    // isBbl: stubbed to true for this skeleton commit; Task 5 will derive from
    // the is_bbl out-param of Model::read_from_file.
    out << "\"isBbl\":true,";
    // fileVersion: stubbed to empty; Task 5 will populate from the file_version
    // out-param.
    out << "\"fileVersion\":\"\",";
    out << "\"plates\":[],";    // Task 5
    out << "\"objects\":[],";   // Task 6
    out << "\"volumes\":[]";    // Task 7
    out << "}";
    return out.str();
}

} // namespace sapil
