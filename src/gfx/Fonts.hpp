#pragma once

// Шрифты с кириллицей. Встроенный шрифт raylib знает только латиницу,
// поэтому грузим TTF/OTF и явно перечисляем нужные символы.

#include <raylib.h>

#include <filesystem>
#include <string>
#include <string_view>
#include <vector>

namespace pix::gfx {

struct Fonts {
    Font crude{};  ///< DejaVu Sans Mono, мелкий и без сглаживания — для стрёмного 2D
    Font mono{};   ///< JetBrains Mono Bold — «технический» текст
    Font ui{};     ///< Inter SemiBold — интерфейс и диалоги красивого 2D
    Font title{};  ///< Inter Black — большие заголовки
};

Fonts loadFonts(const std::filesystem::path& assets);
void unloadFonts(Fonts& fonts);

/// Перенос по словам по ширине в пикселях.
std::vector<std::string> wrapText(const Font& font, std::string_view text, float size, float spacing, float maxWidth);

float textWidth(const Font& font, std::string_view text, float size, float spacing);

/// Текст, выровненный по центру относительно точки (x — центр, y — верх строки).
void drawTextCentered(const Font& font, std::string_view text, Vector2 at, float size, float spacing, Color color);

}  // namespace pix::gfx
