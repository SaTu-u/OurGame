#pragma once

// Сюжет игры хранится не в коде, а в текстовом файле assets/story.txt.
// Сценарист правит текст — программисту ничего пересобирать не нужно.
//
// Формат файла:
//   # комментарий
//   [имя_секции]
//   Алина: реплика персонажа
//   > текст рассказчика (двоеточие внутри не считается именем)
//   просто строка без «Имя: » — тоже рассказчик

#include <filesystem>
#include <map>
#include <string>
#include <string_view>
#include <vector>

namespace pix {

struct DialogueLine {
    std::string speaker;  ///< пусто — говорит рассказчик
    std::string text;

    bool operator==(const DialogueLine&) const = default;
};

/// Разбирает одну строку сценария.
DialogueLine parseDialogueLine(std::string_view line);

class Story {
public:
    static Story parse(std::string_view text);
    /// Если файла нет — возвращает пустой сюжет (игра не падает, а показывает заглушки).
    static Story loadFile(const std::filesystem::path& path);

    [[nodiscard]] bool has(std::string_view section) const;
    /// Строки секции. Для неизвестной секции — одна строка-заглушка, чтобы ошибку было видно в игре.
    [[nodiscard]] std::vector<DialogueLine> section(std::string_view name) const;
    [[nodiscard]] std::size_t sectionCount() const { return sections_.size(); }

private:
    std::map<std::string, std::vector<DialogueLine>, std::less<>> sections_;
};

}  // namespace pix
