#include "terminal/Sectors.hpp"

namespace pix::terminal {

namespace {

constexpr float kNever = 1e9f;
constexpr int kLocked = 99;

std::vector<Sector> makeSectors() {
    // Сектор 1: только биты и пара сонных жуков. Обнуления ещё нет.
    Config awakening;
    awakening.bytesToWin = 2;
    awakening.targetBits = 8;
    awakening.bugsBase = 2;
    awakening.bugsPerByte = 1;
    awakening.bugStepBase = 0.55f;
    awakening.firstWipeDelay = kNever;
    awakening.eraseUnlockBytes = 1;
    awakening.dashUnlockBytes = kLocked;

    // Сектор 2: приходит Обнуление — стоять больше нельзя. Открывается рывок.
    Config archive;
    archive.bytesToWin = 3;
    archive.targetBits = 8;
    archive.bugsBase = 3;
    archive.bugsPerByte = 1;
    archive.firstWipeDelay = 12.f;
    archive.wipeInterval = 20.f;
    archive.eraseUnlockBytes = 0;
    archive.dashUnlockBytes = 1;

    // Сектор 3: тьма. Жуков больше, Обнуление чаще, видно только то, что рядом.
    Config core;
    core.bytesToWin = 3;
    core.targetBits = 8;
    core.bugsBase = 4;
    core.bugsPerByte = 2;
    core.bugStepBase = 0.45f;
    core.firstWipeDelay = 10.f;
    core.wipeInterval = 16.f;
    core.eraseUnlockBytes = 0;
    core.dashUnlockBytes = 0;
    core.visionRadius = 12;

    return {
        {"ПРОБУЖДЕНИЕ", "terminal_1.txt", "terminal_intro", awakening},
        {"АРХИВ", "terminal_2.txt", "terminal_sector2", archive},
        {"ЯДРО", "terminal_3.txt", "terminal_sector3", core},
    };
}

}  // namespace

const std::vector<Sector>& sectors() {
    static const std::vector<Sector> all = makeSectors();
    return all;
}

std::string voiceSection(int sectorIndex, int bytes) {
    return "terminal_s" + std::to_string(sectorIndex + 1) + "_byte" + std::to_string(bytes);
}

}  // namespace pix::terminal
