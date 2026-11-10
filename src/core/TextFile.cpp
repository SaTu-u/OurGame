#include "core/TextFile.hpp"

#include <array>
#include <fstream>
#include <sstream>
#include <system_error>

namespace pix {

namespace fs = std::filesystem;

std::optional<std::string> readTextFile(const fs::path& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return std::nullopt;
    std::ostringstream buffer;
    buffer << in.rdbuf();
    std::string text = buffer.str();
    // Блокнот в Windows любит добавлять BOM в начало UTF-8 файла — выкидываем его.
    if (text.rfind("\xEF\xBB\xBF", 0) == 0) text.erase(0, 3);
    return text;
}

bool writeTextFile(const fs::path& path, std::string_view text) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out.write(text.data(), static_cast<std::streamsize>(text.size()));
    return static_cast<bool>(out);
}

std::vector<std::string> splitLines(std::string_view text) {
    std::vector<std::string> lines;
    std::size_t start = 0;
    while (start <= text.size()) {
        auto end = text.find('\n', start);
        if (end == std::string_view::npos) end = text.size();
        auto line = text.substr(start, end - start);
        if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
        lines.emplace_back(line);
        start = end + 1;
    }
    // Последний перевод строки не создаёт лишнюю пустую строку.
    if (!lines.empty() && lines.back().empty()) lines.pop_back();
    return lines;
}

fs::path findAssetsDir(std::string_view exePath) {
    std::error_code ec;
    std::vector<fs::path> roots;
    if (!exePath.empty()) roots.push_back(fs::absolute(fs::path(exePath), ec).parent_path());
    roots.push_back(fs::current_path(ec));

    for (const auto& root : roots) {
        fs::path dir = root;
        for (int depth = 0; depth < 4 && !dir.empty(); ++depth) {
            const auto candidate = dir / "assets";
            if (fs::exists(candidate / "story.txt", ec)) return candidate;
            if (!dir.has_parent_path() || dir.parent_path() == dir) break;
            dir = dir.parent_path();
        }
    }
#ifdef PTP_SOURCE_ASSETS
    // Запасной вариант для разработки: папка assets из исходников.
    if (fs::exists(fs::path(PTP_SOURCE_ASSETS) / "story.txt", ec)) return fs::path(PTP_SOURCE_ASSETS);
#endif
    return fs::path("assets");
}

}  // namespace pix
