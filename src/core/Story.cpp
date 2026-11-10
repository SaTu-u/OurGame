#include "core/Story.hpp"

#include "core/TextFile.hpp"
#include "core/Utf8.hpp"

namespace pix {

namespace {

constexpr std::size_t kMaxSpeakerLength = 24;

std::string_view trim(std::string_view s) {
    const auto isSpace = [](char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; };
    while (!s.empty() && isSpace(s.front())) s.remove_prefix(1);
    while (!s.empty() && isSpace(s.back())) s.remove_suffix(1);
    return s;
}

}  // namespace

DialogueLine parseDialogueLine(std::string_view line) {
    line = trim(line);
    if (!line.empty() && line.front() == '>') {
        line.remove_prefix(1);
        return {"", std::string(trim(line))};
    }
    const auto colon = line.find(": ");
    if (colon != std::string_view::npos && colon > 0) {
        const auto speaker = trim(line.substr(0, colon));
        if (!speaker.empty() && utf8::length(speaker) <= kMaxSpeakerLength) {
            return {std::string(speaker), std::string(trim(line.substr(colon + 2)))};
        }
    }
    return {"", std::string(line)};
}

Story Story::parse(std::string_view text) {
    Story story;
    std::vector<DialogueLine>* current = nullptr;

    for (const auto& rawLine : splitLines(text)) {
        const auto line = trim(rawLine);
        if (line.empty() || line.front() == '#') continue;

        if (line.front() == '[' && line.back() == ']') {
            const auto name = trim(line.substr(1, line.size() - 2));
            current = &story.sections_[std::string(name)];
            continue;
        }
        if (current != nullptr) current->push_back(parseDialogueLine(line));
    }
    return story;
}

Story Story::loadFile(const std::filesystem::path& path) {
    if (auto text = readTextFile(path)) return parse(*text);
    return {};
}

bool Story::has(std::string_view section) const { return sections_.find(section) != sections_.end(); }

std::vector<DialogueLine> Story::section(std::string_view name) const {
    if (auto it = sections_.find(name); it != sections_.end()) return it->second;
    return {{"", "[в story.txt нет секции «" + std::string(name) + "»]"}};
}

}  // namespace pix
