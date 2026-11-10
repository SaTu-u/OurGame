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

/// Ищет папку assets: рядом с exe, в текущей папке и на пару уровней выше.
/// exePath — argv[0], может быть пустым.
std::filesystem::path findAssetsDir(std::string_view exePath);

}  // namespace pix
