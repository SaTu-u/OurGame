#include "core/Dialogue.hpp"

#include "core/Utf8.hpp"

namespace pix {

Dialogue::Dialogue(std::vector<DialogueLine> lines, float charsPerSecond)
    : lines_(std::move(lines)), charsPerSecond_(charsPerSecond) {}

void Dialogue::update(float dt) {
    if (finished()) return;
    shownChars_ += dt * charsPerSecond_;
    const auto total = static_cast<float>(utf8::length(current().text));
    if (shownChars_ > total) shownChars_ = total;
}

void Dialogue::advance() {
    if (finished()) return;
    if (!lineFullyShown()) {
        shownChars_ = static_cast<float>(utf8::length(current().text));
        return;
    }
    ++index_;
    shownChars_ = 0.f;
}

bool Dialogue::lineFullyShown() const {
    if (finished()) return true;
    return static_cast<std::size_t>(shownChars_) >= utf8::length(current().text);
}

std::string Dialogue::visibleText() const {
    if (finished()) return {};
    return utf8::prefix(current().text, static_cast<std::size_t>(shownChars_));
}

}  // namespace pix
