#include "../include/sapil.h"

#include <algorithm>
#include <chrono>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include <unistd.h>

namespace sapil {
namespace {

std::mutex g_diag_mutex;
std::string g_diag_output_path;
std::string g_native_generation;
std::string g_native_build_id = std::string(SLIC3R_VERSION_FULL) + "/" + std::string(SLIC3R_BUILD_TIME);
bool g_native_init_flushed = false;
constexpr size_t kMaxHistoryLines = 200;

std::string json_escape(const std::string& input)
{
    std::string out;
    out.reserve(input.size() + 16);
    for (char c : input) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

void trim_history_locked()
{
    if (g_diag_output_path.empty()) return;
    std::ifstream input(g_diag_output_path);
    if (! input.good()) return;

    std::vector<std::string> lines;
    std::string line;
    while (std::getline(input, line))
        lines.push_back(line);
    input.close();

    if (lines.size() <= kMaxHistoryLines)
        return;

    std::ofstream output(g_diag_output_path, std::ios::trunc);
    const size_t start = lines.size() - kMaxHistoryLines;
    for (size_t i = start; i < lines.size(); ++i)
        output << lines[i] << '\n';
}

void append_json_line_locked(const std::string& json_line)
{
    if (g_diag_output_path.empty()) return;
    std::ofstream output(g_diag_output_path, std::ios::app);
    if (! output.good()) return;
    output << json_line << '\n';
    output.close();
    trim_history_locked();
}

std::string base_event_json(const std::string& event)
{
    const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    std::ostringstream out;
    out << "{\"type\":\"" << json_escape(event) << "\""
        << ",\"timestampMs\":" << now
        << ",\"pid\":" << getpid()
        << ",\"nativeGeneration\":\"" << json_escape(g_native_generation) << "\""
        << ",\"nativeBuildId\":\"" << json_escape(g_native_build_id) << "\"";
    return out.str();
}

void flush_native_init_locked()
{
    if (g_diag_output_path.empty() || g_native_init_flushed) return;
    append_json_line_locked(base_event_json("native_jni_onload") + "}");
    g_native_init_flushed = true;
}

} // namespace

void diagnostics_set_output_path(const std::string& path)
{
    std::lock_guard<std::mutex> lock(g_diag_mutex);
    g_diag_output_path = path;
    flush_native_init_locked();
}

std::string diagnostics_get_state_json()
{
    std::lock_guard<std::mutex> lock(g_diag_mutex);
    std::ostringstream out;
    out << "{"
        << "\"pid\":" << getpid() << ","
        << "\"nativeGeneration\":\"" << json_escape(g_native_generation) << "\","
        << "\"nativeBuildId\":\"" << json_escape(g_native_build_id) << "\","
        << "\"diagnosticsConfigured\":" << (g_diag_output_path.empty() ? "false" : "true")
        << "}";
    return out.str();
}

void diagnostics_record_native_event(const std::string& event, const std::string& payload_json)
{
    std::lock_guard<std::mutex> lock(g_diag_mutex);
    if (g_native_generation.empty()) {
        g_native_generation = std::to_string(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count()) +
            "-" + std::to_string(getpid());
    }
    flush_native_init_locked();
    std::string payload = payload_json.empty() ? "{}" : payload_json;
    append_json_line_locked(base_event_json(event) + ",\"payload\":" + payload + "}");
}

void diagnostics_note_clipper_point(long long x, long long y, const char* source)
{
    std::ostringstream payload;
    payload << "{"
            << "\"source\":\"" << json_escape(source ? source : "unknown") << "\","
            << "\"x\":" << x << ","
            << "\"y\":" << y
            << "}";
    diagnostics_record_native_event("clipper_coordinate_out_of_range", payload.str());
}

} // namespace sapil
