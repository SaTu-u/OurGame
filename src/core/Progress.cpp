#include "core/Progress.hpp"

#include <charconv>
#include <cstdio>
#include <sstream>

#include "core/TextFile.hpp"

namespace pix {

namespace {

struct StageInfo {
    StageId id;
    std::string_view name;
};

constexpr StageInfo kStages[] = {
    {StageId::Terminal, "terminal"},
    {StageId::Crude, "crude"},
    {StageId::Pretty, "pretty"},
    {StageId::Finale, "finale"},
};

template <typename T>
bool parseNumber(std::string_view text, T& out) {
    T value{};
    const auto* end = text.data() + text.size();
    const auto [ptr, ec] = std::from_chars(text.data(), end, value);
    if (ec != std::errc{} || ptr != end) return false;
    out = value;
    return true;
}

// std::from_chars для double есть не во всех компиляторах (старый clang на macOS),
// поэтому время храним в целых миллисекундах.
bool parseSeconds(std::string_view text, double& out) {
    long long millis = 0;
    if (!parseNumber(text, millis) || millis < 0) return false;
    out = static_cast<double>(millis) / 1000.0;
    return true;
}

}  // namespace

std::string_view stageName(StageId id) {
    for (const auto& s : kStages) {
        if (s.id == id) return s.name;
    }
    return "unknown";
}

std::optional<StageId> parseStage(std::string_view text) {
    for (const auto& s : kStages) {
        if (text == s.name) return s.id;
    }
    int number = 0;
    if (parseNumber(text, number) && number >= 1 && number <= 4) return static_cast<StageId>(number);
    return std::nullopt;
}

std::string Progress::serialize() const {
    std::ostringstream out;
    out << "# Сохранение «Из пикселя в люди». Можно удалить, чтобы начать заново.\n";
    out << "stage=" << stageName(stage) << '\n';
    out << "level=" << level << '\n';
    out << "double_jump=" << (doubleJump ? 1 : 0) << '\n';
    out << "dash=" << (dash ? 1 : 0) << '\n';
    out << "bits=" << bits << '\n';
    out << "pixels=" << pixels << '\n';
    out << "shards=" << shards << '\n';
    out << "deaths=" << deaths << '\n';
    out << "play_ms=" << static_cast<long long>(playSeconds * 1000.0) << '\n';
    return out.str();
}

Progress Progress::deserialize(std::string_view text) {
    Progress p;
    for (const auto& line : splitLines(text)) {
        if (line.empty() || line.front() == '#') continue;
        const auto eq = line.find('=');
        if (eq == std::string::npos) continue;
        const std::string_view key(line.data(), eq);
        const std::string_view value(line.data() + eq + 1, line.size() - eq - 1);

        int flag = 0;
        if (key == "stage") {
            if (auto s = parseStage(value)) p.stage = *s;
        } else if (key == "level") {
            parseNumber(value, p.level);
        } else if (key == "double_jump" && parseNumber(value, flag)) {
            p.doubleJump = flag != 0;
        } else if (key == "dash" && parseNumber(value, flag)) {
            p.dash = flag != 0;
        } else if (key == "bits") {
            parseNumber(value, p.bits);
        } else if (key == "pixels") {
            parseNumber(value, p.pixels);
        } else if (key == "shards") {
            parseNumber(value, p.shards);
        } else if (key == "deaths") {
            parseNumber(value, p.deaths);
        } else if (key == "play_ms") {
            parseSeconds(value, p.playSeconds);
        }
    }
    if (p.level < 0) p.level = 0;
    return p;
}

bool saveProgress(const Progress& progress, const std::filesystem::path& path) {
    return writeTextFile(path, progress.serialize());
}

std::optional<Progress> loadProgress(const std::filesystem::path& path) {
    if (auto text = readTextFile(path)) return Progress::deserialize(*text);
    return std::nullopt;
}

}  // namespace pix
