#pragma once

// Стадия 1 — «Терминал». Игра запускается прямо в консоли:
// загрузка выдуманной PIXEL OS, сюжетные заставки и три сектора игры за пиксель.

#include <filesystem>
#include <functional>

#include "core/Progress.hpp"
#include "core/Story.hpp"

namespace pix::terminal {

enum class TerminalOutcome {
    Escaped,         ///< Тимур прошёл все секторы — дальше 2D
    Quit,            ///< игрок вышел из игры
    NotInteractive,  ///< нет настоящего терминала (например, окно вывода IDE)
};

struct TerminalOptions {
    bool demo = false;           ///< режим показа: клавиша N пропускает сектор
    std::function<void()> save;  ///< сохранить прогресс (вызывается после каждого сектора)
};

/// levelsDir — папка assets/levels с картами секторов.
/// Бросает std::runtime_error, если карта сектора не найдена или испорчена.
TerminalOutcome runTerminalStage(const Story& story, const std::filesystem::path& levelsDir, Progress& progress,
                                 const TerminalOptions& options);

}  // namespace pix::terminal
