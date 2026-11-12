#pragma once

// Общие вещи для графических стадий (всё, что работает поверх raylib).

#include <raylib.h>

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <functional>

#include "core/Progress.hpp"
#include "core/Story.hpp"
#include "gfx/Fonts.hpp"

namespace pix::gfx {

/// То, что нужно любой графической стадии.
struct GameContext {
    std::filesystem::path assets;
    const Story* story = nullptr;
    const Fonts* fonts = nullptr;
    std::function<void()> save;  ///< сохранить прогресс на диск
    bool noVideo = false;
    bool demo = false;  ///< режим показа: клавиша N пропускает уровень
};

/// Виртуальный экран фиксированного размера. Стадия рисует в него,
/// а он растягивается на окно любого размера с чёрными полосами по краям.
/// Для стрёмного 2D — 320x180 и «пиксельное» растяжение без сглаживания.
class VirtualScreen {
public:
    VirtualScreen(int width, int height, bool pixelated) : width_(width), height_(height), pixelated_(pixelated) {
        target_ = LoadRenderTexture(width, height);
        SetTextureFilter(target_.texture, pixelated ? TEXTURE_FILTER_POINT : TEXTURE_FILTER_BILINEAR);
    }
    ~VirtualScreen() { UnloadRenderTexture(target_); }
    VirtualScreen(const VirtualScreen&) = delete;
    VirtualScreen& operator=(const VirtualScreen&) = delete;

    void begin() const { BeginTextureMode(target_); }
    void end() const { EndTextureMode(); }

    /// Рисует виртуальный экран в окно. Вызывать между BeginDrawing/EndDrawing.
    void present(Vector2 shake = {0.f, 0.f}) const {
        const auto winW = static_cast<float>(GetScreenWidth());
        const auto winH = static_cast<float>(GetScreenHeight());
        float scale = std::min(winW / static_cast<float>(width_), winH / static_cast<float>(height_));
        if (pixelated_ && scale >= 1.f) scale = std::floor(scale);  // ровные пиксели
        const float w = static_cast<float>(width_) * scale;
        const float h = static_cast<float>(height_) * scale;
        const Rectangle src{0.f, 0.f, static_cast<float>(width_), -static_cast<float>(height_)};
        const Rectangle dst{(winW - w) * 0.5f + shake.x * scale, (winH - h) * 0.5f + shake.y * scale, w, h};
        DrawTexturePro(target_.texture, src, dst, {0.f, 0.f}, 0.f, WHITE);
    }

    [[nodiscard]] int width() const { return width_; }
    [[nodiscard]] int height() const { return height_; }
    [[nodiscard]] const Texture2D& texture() const { return target_.texture; }

private:
    int width_;
    int height_;
    bool pixelated_;
    RenderTexture2D target_{};
};

// ---------------------------------------------------------------- маленькие помощники

inline float lerp(float a, float b, float t) { return a + (b - a) * t; }
inline float clamp01(float v) { return std::clamp(v, 0.f, 1.f); }
inline float easeOutCubic(float t) { return 1.f - std::pow(1.f - clamp01(t), 3.f); }

/// Плавное приближение, не зависящее от FPS: за 1 секунду проходит долю (1 - 2^-speed).
inline float damp(float current, float target, float speed, float dt) {
    return lerp(current, target, 1.f - std::exp2(-speed * dt));
}

inline Color withAlpha(Color c, float alpha) {
    c.a = static_cast<unsigned char>(clamp01(alpha) * 255.f);
    return c;
}

inline Color mix(Color a, Color b, float t) {
    t = clamp01(t);
    return {static_cast<unsigned char>(lerp(a.r, b.r, t)), static_cast<unsigned char>(lerp(a.g, b.g, t)),
            static_cast<unsigned char>(lerp(a.b, b.b, t)), static_cast<unsigned char>(lerp(a.a, b.a, t))};
}

/// Нажата ли «кнопка согласия»: Enter, пробел, E или кнопка A на геймпаде.
inline bool confirmPressed() {
    return IsKeyPressed(KEY_ENTER) || IsKeyPressed(KEY_KP_ENTER) || IsKeyPressed(KEY_SPACE) || IsKeyPressed(KEY_E) ||
           IsMouseButtonPressed(MOUSE_BUTTON_LEFT) ||
           (IsGamepadAvailable(0) && IsGamepadButtonPressed(0, GAMEPAD_BUTTON_RIGHT_FACE_DOWN));
}

}  // namespace pix::gfx
