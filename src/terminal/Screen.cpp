#include "terminal/Screen.hpp"

#include <algorithm>
#include <string>

#include "core/Utf8.hpp"

namespace pix::terminal {

namespace {

int ansiCode(Color c, bool background) {
    const int base = background ? 40 : 30;
    const int bright = background ? 100 : 90;
    switch (c) {
        case Color::Default: return background ? 49 : 39;
        case Color::Black: return base + 0;
        case Color::Red: return base + 1;
        case Color::Green: return base + 2;
        case Color::Yellow: return base + 3;
        case Color::Blue: return base + 4;
        case Color::Magenta: return base + 5;
        case Color::Cyan: return base + 6;
        case Color::White: return base + 7;
        case Color::Gray: return bright + 0;
        case Color::BrightRed: return bright + 1;
        case Color::BrightGreen: return bright + 2;
        case Color::BrightYellow: return bright + 3;
        case Color::BrightBlue: return bright + 4;
        case Color::BrightMagenta: return bright + 5;
        case Color::BrightCyan: return bright + 6;
        case Color::BrightWhite: return bright + 7;
    }
    return 0;
}

const Glyph kOutside{};

}  // namespace

std::string ansiStyle(const Style& style) {
    std::string s = "\x1b[0";
    if (style.bold) s += ";1";
    s += ';' + std::to_string(ansiCode(style.fg, false));
    s += ';' + std::to_string(ansiCode(style.bg, true));
    s += 'm';
    return s;
}

Screen::Screen(int width, int height)
    : width_(width), height_(height), cells_(static_cast<std::size_t>(width * height)) {}

void Screen::clear() { std::fill(cells_.begin(), cells_.end(), Glyph{}); }

void Screen::put(int x, int y, char32_t ch, Style style) {
    if (x < 0 || y < 0 || x >= width_ || y >= height_) return;
    cells_[static_cast<std::size_t>(y * width_ + x)] = {ch, style};
}

int Screen::text(int x, int y, std::string_view utf8, Style style) {
    int written = 0;
    for (char32_t ch : utf8::decode(utf8)) put(x + written++, y, ch, style);
    return written;
}

void Screen::fill(int x, int y, int w, int h, char32_t ch, Style style) {
    for (int j = 0; j < h; ++j) {
        for (int i = 0; i < w; ++i) put(x + i, y + j, ch, style);
    }
}

void Screen::frame(int x, int y, int w, int h, Style style) {
    if (w < 2 || h < 2) return;
    for (int i = 1; i < w - 1; ++i) {
        put(x + i, y, U'─', style);
        put(x + i, y + h - 1, U'─', style);
    }
    for (int j = 1; j < h - 1; ++j) {
        put(x, y + j, U'│', style);
        put(x + w - 1, y + j, U'│', style);
    }
    put(x, y, U'┌', style);
    put(x + w - 1, y, U'┐', style);
    put(x, y + h - 1, U'└', style);
    put(x + w - 1, y + h - 1, U'┘', style);
}

const Glyph& Screen::at(int x, int y) const {
    if (x < 0 || y < 0 || x >= width_ || y >= height_) return kOutside;
    return cells_[static_cast<std::size_t>(y * width_ + x)];
}

std::string Screen::diff(int originX, int originY) {
    const bool full = previous_.size() != cells_.size();
    std::string out;
    out.reserve(full ? cells_.size() * 4 : 256);

    Style current;
    bool styleKnown = false;
    int cursorX = -1;
    int cursorY = -1;

    for (int y = 0; y < height_; ++y) {
        for (int x = 0; x < width_; ++x) {
            const auto i = static_cast<std::size_t>(y * width_ + x);
            const Glyph& g = cells_[i];
            if (!full && previous_[i] == g) continue;

            if (cursorX != x || cursorY != y) {
                out += "\x1b[" + std::to_string(originY + y + 1) + ';' + std::to_string(originX + x + 1) + 'H';
            }
            if (!styleKnown || !(current == g.style)) {
                out += ansiStyle(g.style);
                current = g.style;
                styleKnown = true;
            }
            utf8::append(out, g.ch);
            cursorX = x + 1;
            cursorY = y;
        }
    }
    if (!out.empty()) out += "\x1b[0m";
    previous_ = cells_;
    return out;
}

}  // namespace pix::terminal
