#pragma once

// Аргументы командной строки. Особенно полезны на защите проекта:
// `--stage 3` сразу показывает красивое 2D, не проходя всю игру.

#include <optional>
#include <string>
#include <vector>

#include "core/Progress.hpp"

namespace pix {

struct Options {
    std::optional<StageId> startStage;  ///< --stage N
    int startLevel = 0;                 ///< --level M (в файле — с нуля, в командной строке — с единицы)
    bool demo = false;                  ///< --demo: клавиша N пропускает уровень (для показа на защите)
    bool fresh = false;                 ///< --new: игнорировать сохранение
    bool showHelp = false;              ///< --help
    bool noVideo = false;               ///< --no-video: не открывать видео в финале
    std::vector<std::string> errors;
};

Options parseOptions(const std::vector<std::string>& args);
std::string usageText();

}  // namespace pix
