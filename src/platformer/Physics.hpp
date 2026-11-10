#pragma once

// Физика платформера: гравитация и столкновения прямоугольника с тайлами.
// Одна и та же физика работает и в стрёмном, и в красивом 2D —
// отличаются только настройки (PhysicsConfig::crude / ::polished).

#include "platformer/Math.hpp"
#include "platformer/TileMap.hpp"

namespace pix::platformer {

struct PhysicsConfig {
    float gravity = 75.f;       ///< клеток/с²
    float maxFallSpeed = 24.f;  ///< клеток/с
    float runSpeed = 8.f;
    float groundAccel = 80.f;
    float airAccel = 50.f;
    float groundFriction = 70.f;
    float jumpSpeed = 20.5f;
    float airJumpFactor = 0.9f;     ///< второй прыжок чуть слабее первого
    float jumpCutMultiplier = 1.f;  ///< <1 — отпустил прыжок раньше, прыгнул ниже
    float coyoteTime = 0.f;         ///< можно прыгнуть чуть после того, как сошёл с края
    float jumpBufferTime = 0.f;     ///< нажал прыжок чуть до приземления — всё равно прыгнешь
    float dashSpeed = 26.f;
    float dashDuration = 0.16f;
    float dashCooldown = 0.6f;
    float stompBounce = 0.75f;
    float invulnTime = 1.f;
    float enemySpeed = 2.5f;

    /// Стрёмное 2D: деревянное управление, никаких поблажек.
    static PhysicsConfig crude();
    /// Красивое 2D: «койотское время», буфер прыжка, прыжок зависит от того, сколько держишь кнопку.
    static PhysicsConfig polished();
};

struct Body {
    Vec2 pos;   ///< левый верхний угол
    Vec2 size{1.f, 1.f};
    Vec2 vel;
    bool onGround = false;
    bool hitWall = false;
    bool hitCeiling = false;

    [[nodiscard]] Rect rect() const { return {pos.x, pos.y, size.x, size.y}; }
    [[nodiscard]] Vec2 center() const { return rect().center(); }
};

/// Двигает тело на vel*dt, по осям отдельно, и выталкивает из твёрдых тайлов.
void moveAndCollide(Body& body, const TileMap& map, float dt);

/// Касается ли прямоугольник шипов (у шипов хитбокс — нижняя половина клетки).
bool touchesSpikes(const Rect& r, const TileMap& map);

/// Есть ли твёрдый тайл в точке.
bool solidAt(const TileMap& map, Vec2 p);

}  // namespace pix::platformer
