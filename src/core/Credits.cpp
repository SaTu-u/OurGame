#include "core/Credits.hpp"

#include "core/TextFile.hpp"

namespace pix {

namespace {

std::string trim(std::string_view s) {
    while (!s.empty() && (s.front() == ' ' || s.front() == '\t')) s.remove_prefix(1);
    while (!s.empty() && (s.back() == ' ' || s.back() == '\t' || s.back() == '\r')) s.remove_suffix(1);
    return std::string(s);
}

}  // namespace

std::vector<CreditSection> parseCredits(std::string_view text) {
    std::vector<CreditSection> sections;
    for (const auto& raw : splitLines(text)) {
        const std::string line = trim(raw);
        if (line.empty() || line.front() == '#') continue;
        if (line.front() == '[' && line.back() == ']') {
            sections.push_back({trim(std::string_view(line).substr(1, line.size() - 2)), {}});
            continue;
        }
        if (sections.empty()) sections.push_back({"", {}});
        const auto bar = line.find('|');
        if (bar == std::string::npos) {
            sections.back().entries.push_back({"", line});
        } else {
            sections.back().entries.push_back(
                {trim(std::string_view(line).substr(0, bar)), trim(std::string_view(line).substr(bar + 1))});
        }
    }
    return sections;
}

}  // namespace pix
