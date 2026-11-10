#pragma once

// Стадия 1 — «Терминал». Игра запускается прямо в консоли:
// загрузка выдуманной PIXEL OS, сюжетное вступление и сама игра за пиксель.

#include <string>
#include <vector>

#include "core/Progress.hpp"
#include "core/Story.hpp"

namespace pix::terminal {

enum class TerminalOutcome {
    Escaped,         ///< пиксель добрался до видеодрайвера — дальше 2D
    Quit,            ///< игрок вышел из игры
    NotInteractive,  ///< нет настоящего терминала (например, окно вывода IDE)
};

TerminalOutcome runTerminalStage(const Story& story, const std::vector<std::string>& mapLines, Progress& progress);

}  // namespace pix::terminal
