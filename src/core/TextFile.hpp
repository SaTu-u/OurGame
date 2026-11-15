#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace pix {

/// Читает файл целиком. std::nullopt — если файла нет или он не читается.
std::optional<std::string> readTextFile(const std::filesystem::path& path);

/// Записывает файл целиком. false — если записать не удалось.
bool writeTextFile(const std::filesystem::path& path, std::string_view text);

/// Делит текст на строки, понимает и \n, и \r\n (файлы, сохранённые в Windows).
std::vector<std::string> splitLines(std::string_view text);

/// Ищет папку assets: в текущей папке, на пару уровней выше и рядом с exe.
/// Если нашлась относительно текущей папки — возвращает относительный путь (assets, ../assets):
/// в нём нет русских букв, и его безопасно отдавать raylib даже в Windows.
std::filesystem::path findAssetsDir();

}  // namespace pix
