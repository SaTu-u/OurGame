#include "core/TextFile.hpp"

#include "core/Platform.hpp"

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

fs::path findAssetsDir() {
    std::error_code ec;
    // Сначала относительно текущей папки — так путь останется относительным.
    fs::path relative = "assets";
    for (int depth = 0; depth < 4; ++depth) {
        if (fs::exists(relative / "story.txt", ec)) return relative;
        relative = ".." / relative;
    }
    // Потом рядом с exe (main обычно уже перешёл туда через platform::enterGameDirectory).
    if (const fs::path exeDir = platform::executableDir(); !exeDir.empty()) {
        if (fs::exists(exeDir / "assets" / "story.txt", ec)) return exeDir / "assets";
    }
#ifdef PTP_SOURCE_ASSETS
    // Запасной вариант для разработки: папка assets из исходников.
    if (fs::exists(fs::path(PTP_SOURCE_ASSETS) / "story.txt", ec)) return fs::path(PTP_SOURCE_ASSETS);
#endif
    return fs::path("assets");
}

}  // namespace pix
