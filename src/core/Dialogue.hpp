#pragma once

// Диалог с эффектом печатной машинки. Ничего не знает о графике:
// терминал, кривое 2D и красивое 2D рисуют один и тот же Dialogue по-своему.

#include <cstddef>
#include <string>
#include <vector>

#include "core/Story.hpp"

namespace pix {

class Dialogue {
public:
    explicit Dialogue(std::vector<DialogueLine> lines, float charsPerSecond = 40.f);

    void update(float dt);
    /// Строка ещё печатается — показать её целиком; уже показана — перейти к следующей.
    void advance();

    [[nodiscard]] bool finished() const { return index_ >= lines_.size(); }
    [[nodiscard]] bool lineFullyShown() const;
    /// Текущая строка. Нельзя вызывать, если finished().
    [[nodiscard]] const DialogueLine& current() const { return lines_[index_]; }
    /// Уже «напечатанная» часть текущей строки.
    [[nodiscard]] std::string visibleText() const;
    [[nodiscard]] std::size_t index() const { return index_; }
    [[nodiscard]] std::size_t size() const { return lines_.size(); }

private:
    std::vector<DialogueLine> lines_;
    std::size_t index_ = 0;
    float shownChars_ = 0.f;
    float charsPerSecond_;
};

}  // namespace pix
