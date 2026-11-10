#pragma once

// Экранный буфер терминала: сетка символов с цветами.
// Каждый кадр мы рисуем в буфер, а в терминал отправляем только то, что изменилось
// с прошлого кадра, — так картинка не мерцает даже в медленной консоли Windows.

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace pix::terminal {

enum class Color : std::uint8_t {
    Default,
    Black, Red, Green, Yellow, Blue, Magenta, Cyan, White,
    Gray, BrightRed, BrightGreen, BrightYellow, BrightBlue, BrightMagenta, BrightCyan, BrightWhite,
};

struct Style {
    Color fg = Color::Default;
    Color bg = Color::Default;
    bool bold = false;
    bool operator==(const Style&) const = default;
};

struct Glyph {
    char32_t ch = U' ';
    Style style;
    bool operator==(const Glyph&) const = default;
};

class Screen {
public:
    Screen(int width, int height);

    [[nodiscard]] int width() const { return width_; }
    [[nodiscard]] int height() const { return height_; }

    void clear();
    void put(int x, int y, char32_t ch, Style style = {});
    /// Пишет UTF-8 текст начиная с (x, y), обрезая по краю экрана. Возвращает ширину текста.
    int text(int x, int y, std::string_view utf8, Style style = {});
    void fill(int x, int y, int w, int h, char32_t ch, Style style = {});
    void frame(int x, int y, int w, int h, Style style = {});
    [[nodiscard]] const Glyph& at(int x, int y) const;

    /// ANSI-строка с изменениями относительно прошлого кадра.
    /// originX/originY — где на экране терминала находится левый верхний угол буфера.
    std::string diff(int originX, int originY);
    /// Следующий diff() перерисует всё (например, после изменения размера окна).
    void invalidate() { previous_.clear(); }

private:
    int width_;
    int height_;
    std::vector<Glyph> cells_;
    std::vector<Glyph> previous_;
};

/// SGR-последовательность ANSI для стиля.
std::string ansiStyle(const Style& style);

}  // namespace pix::terminal
