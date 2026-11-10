#pragma once

// Простейшая геометрия для платформера. Единица длины — одна клетка уровня (тайл),
// ось Y смотрит вниз, как на экране.

#include <algorithm>
#include <cmath>

namespace pix::platformer {

struct Vec2 {
    float x = 0.f;
    float y = 0.f;

    constexpr Vec2 operator+(Vec2 o) const { return {x + o.x, y + o.y}; }
    constexpr Vec2 operator-(Vec2 o) const { return {x - o.x, y - o.y}; }
    constexpr Vec2 operator*(float k) const { return {x * k, y * k}; }
    constexpr Vec2& operator+=(Vec2 o) {
        x += o.x;
        y += o.y;
        return *this;
    }
    [[nodiscard]] float length() const { return std::sqrt(x * x + y * y); }
};

struct Rect {
    float x = 0.f;
    float y = 0.f;
    float w = 0.f;
    float h = 0.f;

    [[nodiscard]] constexpr float left() const { return x; }
    [[nodiscard]] constexpr float right() const { return x + w; }
    [[nodiscard]] constexpr float top() const { return y; }
    [[nodiscard]] constexpr float bottom() const { return y + h; }
    [[nodiscard]] constexpr Vec2 center() const { return {x + w * 0.5f, y + h * 0.5f}; }

    [[nodiscard]] constexpr bool intersects(const Rect& o) const {
        return x < o.right() && right() > o.x && y < o.bottom() && bottom() > o.y;
    }
};

/// Сдвигает value к target не больше чем на delta.
inline float approach(float value, float target, float delta) {
    if (value < target) return std::min(value + delta, target);
    return std::max(value - delta, target);
}

inline float distance(Vec2 a, Vec2 b) { return (a - b).length(); }

}  // namespace pix::platformer
