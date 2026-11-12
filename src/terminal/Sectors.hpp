#pragma once

// Секторы терминальной стадии. Каждый сектор — своя карта и свои правила:
// в первом Тимур учится ходить, во втором появляется Обнуление, в третьем гаснет свет.

#include <string>
#include <vector>

#include "terminal/TerminalWorld.hpp"

namespace pix::terminal {

struct Sector {
    std::string title;    ///< название для заставки и панели
    std::string mapFile;  ///< файл карты в assets/levels
    std::string intro;    ///< секция сюжета перед сектором
    Config config;        ///< баланс именно этого сектора
};

const std::vector<Sector>& sectors();

/// Имя секции с репликой, которая звучит после очередного байта (например, terminal_s2_byte1).
std::string voiceSection(int sectorIndex, int bytes);

}  // namespace pix::terminal
