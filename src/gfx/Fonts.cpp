#include "gfx/Fonts.hpp"

#include "core/Utf8.hpp"

namespace pix::gfx {

namespace {

std::vector<int> gameCodepoints() {
    std::vector<int> cps;
    for (int c = 32; c <= 126; ++c) cps.push_back(c);         // латиница, цифры, знаки
    for (int c = 0x0410; c <= 0x044F; ++c) cps.push_back(c);  // А..я
    for (int c : {0x0401, 0x0451,                              // Ё ё
                  0x00AB, 0x00BB, 0x2014, 0x2013, 0x2026,      // « » — – …
                  0x2116, 0x00B7, 0x2022, 0x00D7,              // № · • ×
                  0x2190, 0x2191, 0x2192, 0x2193, 0x25B6, 0x2665}) {  // стрелки ▶ ♥
        cps.push_back(c);
    }
    return cps;
}

Font loadFont(const std::filesystem::path& path, int size, int filter, bool mipmaps) {
    auto cps = gameCodepoints();
    Font font = LoadFontEx(path.string().c_str(), size, cps.data(), static_cast<int>(cps.size()));
    if (font.texture.id == 0 || font.glyphCount == 0) {
        TraceLog(LOG_WARNING, "Не удалось загрузить шрифт %s — будет латиница по умолчанию", path.string().c_str());
        return GetFontDefault();
    }
    if (mipmaps) GenTextureMipmaps(&font.texture);
    SetTextureFilter(font.texture, filter);
    return font;
}

void unloadIfCustom(Font& font) {
    if (font.texture.id != 0 && font.texture.id != GetFontDefault().texture.id) UnloadFont(font);
    font = Font{};
}

}  // namespace

Fonts loadFonts(const std::filesystem::path& assets) {
    const auto dir = assets / "fonts";
    Fonts f;
    f.crude = loadFont(dir / "DejaVuSansMono.ttf", 10, TEXTURE_FILTER_POINT, false);
    f.mono = loadFont(dir / "JetBrainsMono-Bold.ttf", 40, TEXTURE_FILTER_TRILINEAR, true);
    f.ui = loadFont(dir / "Inter-SemiBold.otf", 56, TEXTURE_FILTER_TRILINEAR, true);
    f.title = loadFont(dir / "Inter-Black.otf", 112, TEXTURE_FILTER_TRILINEAR, true);
    return f;
}

void unloadFonts(Fonts& fonts) {
    unloadIfCustom(fonts.crude);
    unloadIfCustom(fonts.mono);
    unloadIfCustom(fonts.ui);
    unloadIfCustom(fonts.title);
}

float textWidth(const Font& font, std::string_view text, float size, float spacing) {
    const std::string s(text);
    return MeasureTextEx(font, s.c_str(), size, spacing).x;
}

std::vector<std::string> wrapText(const Font& font, std::string_view text, float size, float spacing, float maxWidth) {
    std::vector<std::string> lines;
    std::string line;
    std::size_t pos = 0;
    while (pos <= text.size()) {
        auto end = text.find(' ', pos);
        if (end == std::string_view::npos) end = text.size();
        const std::string word(text.substr(pos, end - pos));
        pos = end + 1;
        if (word.empty()) {
            if (end == text.size()) break;
            continue;
        }
        const std::string candidate = line.empty() ? word : line + ' ' + word;
        if (!line.empty() && textWidth(font, candidate, size, spacing) > maxWidth) {
            lines.push_back(line);
            line = word;
        } else {
            line = candidate;
        }
        if (end == text.size()) break;
    }
    if (!line.empty() || lines.empty()) lines.push_back(line);
    return lines;
}

void drawTextCentered(const Font& font, std::string_view text, Vector2 at, float size, float spacing, Color color) {
    const std::string s(text);
    const float w = MeasureTextEx(font, s.c_str(), size, spacing).x;
    DrawTextEx(font, s.c_str(), {at.x - w * 0.5f, at.y}, size, spacing, color);
}

}  // namespace pix::gfx
