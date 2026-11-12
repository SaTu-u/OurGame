#pragma once

// Прогресс игрока, который переходит из стадии в стадию:
// что открыто, сколько собрано, сколько раз умер. Сохраняется в обычный текстовый файл.

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>

namespace pix {

/// Стадии «эволюции» игры. Номера совпадают с аргументом --stage.
enum class StageId : int {
    Terminal = 1,  ///< консоль: ты — пиксель
    Crude = 2,     ///< стрёмное 2D
    Pretty = 3,    ///< красивое 2D
    Finale = 4,    ///< выход в реальность (видео)
};

std::string_view stageName(StageId id);
/// Понимает и номер («2»), и имя («crude»).
std::optional<StageId> parseStage(std::string_view text);

struct Progress {
    StageId stage = StageId::Terminal;
    int level = 0;  ///< номер уровня (или сектора терминала) внутри стадии
    bool doubleJump = false;  ///< научила Алина
    bool dash = false;        ///< научил Егор
    int bits = 0;    ///< биты, собранные в терминале
    int pixels = 0;  ///< пиксели, собранные в 2D
    int shards = 0;  ///< найденные осколки памяти
    int deaths = 0;
    double playSeconds = 0.0;

    [[nodiscard]] std::string serialize() const;
    /// Терпим к мусору: незнакомые ключи и кривые значения просто пропускаются.
    static Progress deserialize(std::string_view text);

    bool operator==(const Progress&) const = default;
};

bool saveProgress(const Progress& progress, const std::filesystem::path& path);
std::optional<Progress> loadProgress(const std::filesystem::path& path);

}  // namespace pix
