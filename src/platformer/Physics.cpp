#include "platformer/Physics.hpp"

#include <cmath>

namespace pix::platformer {

namespace {

constexpr float kEpsilon = 1e-4f;

int cellOf(float v) { return static_cast<int>(std::floor(v)); }

}  // namespace

PhysicsConfig PhysicsConfig::crude() {
    PhysicsConfig c;
    c.gravity = 75.f;
    c.runSpeed = 8.f;
    c.groundAccel = 60.f;
    c.airAccel = 40.f;
    c.groundFriction = 45.f;  // немного скользко — «кривая» физика
    c.jumpSpeed = 20.5f;
    c.jumpCutMultiplier = 1.f;
    c.coyoteTime = 0.03f;
    c.jumpBufferTime = 0.03f;
    c.enemySpeed = 2.5f;
    return c;
}

PhysicsConfig PhysicsConfig::polished() {
    PhysicsConfig c;
    c.gravity = 80.f;
    c.runSpeed = 9.5f;
    c.groundAccel = 110.f;
    c.airAccel = 75.f;
    c.groundFriction = 120.f;
    c.jumpSpeed = 21.5f;
    c.jumpCutMultiplier = 0.45f;
    c.coyoteTime = 0.1f;
    c.jumpBufferTime = 0.12f;
    c.dashSpeed = 28.f;
    c.dashCooldown = 0.55f;
    c.enemySpeed = 3.5f;
    return c;
}

void moveAndCollide(Body& body, const TileMap& map, float dt) {
    body.onGround = false;
    body.hitWall = false;
    body.hitCeiling = false;

    // --- по X ---
    body.pos.x += body.vel.x * dt;
    {
        const int top = cellOf(body.pos.y + kEpsilon);
        const int bottom = cellOf(body.pos.y + body.size.y - kEpsilon);
        if (body.vel.x > 0.f) {
            const int col = cellOf(body.pos.x + body.size.x - kEpsilon);
            for (int y = top; y <= bottom; ++y) {
                if (map.solid(col, y)) {
                    body.pos.x = static_cast<float>(col) - body.size.x;
                    body.vel.x = 0.f;
                    body.hitWall = true;
                    break;
                }
            }
        } else if (body.vel.x < 0.f) {
            const int col = cellOf(body.pos.x + kEpsilon);
            for (int y = top; y <= bottom; ++y) {
                if (map.solid(col, y)) {
                    body.pos.x = static_cast<float>(col + 1);
                    body.vel.x = 0.f;
                    body.hitWall = true;
                    break;
                }
            }
        }
    }

    // --- по Y ---
    body.pos.y += body.vel.y * dt;
    {
        const int left = cellOf(body.pos.x + kEpsilon);
        const int right = cellOf(body.pos.x + body.size.x - kEpsilon);
        if (body.vel.y > 0.f) {
            const int row = cellOf(body.pos.y + body.size.y - kEpsilon);
            for (int x = left; x <= right; ++x) {
                if (map.solid(x, row)) {
                    body.pos.y = static_cast<float>(row) - body.size.y;
                    body.vel.y = 0.f;
                    body.onGround = true;
                    break;
                }
            }
        } else if (body.vel.y < 0.f) {
            const int row = cellOf(body.pos.y + kEpsilon);
            for (int x = left; x <= right; ++x) {
                if (map.solid(x, row)) {
                    body.pos.y = static_cast<float>(row + 1);
                    body.vel.y = 0.f;
                    body.hitCeiling = true;
                    break;
                }
            }
        }
    }

    // Стоим ровно на полу (скорость 0) — всё равно считаемся на земле.
    if (!body.onGround && body.vel.y >= 0.f) {
        const int row = cellOf(body.pos.y + body.size.y + 0.01f);
        const int left = cellOf(body.pos.x + kEpsilon);
        const int right = cellOf(body.pos.x + body.size.x - kEpsilon);
        const bool flush = std::abs(body.pos.y + body.size.y - static_cast<float>(row)) < 0.02f;
        for (int x = left; x <= right && flush; ++x) {
            if (map.solid(x, row)) {
                body.onGround = true;
                break;
            }
        }
    }
}

bool touchesSpikes(const Rect& r, const TileMap& map) {
    const int left = cellOf(r.left());
    const int right = cellOf(r.right() - kEpsilon);
    const int top = cellOf(r.top());
    const int bottom = cellOf(r.bottom() - kEpsilon);
    for (int y = top; y <= bottom; ++y) {
        for (int x = left; x <= right; ++x) {
            if (map.at(x, y) != Tile::Spike) continue;
            const Rect spike{static_cast<float>(x) + 0.15f, static_cast<float>(y) + 0.5f, 0.7f, 0.5f};
            if (spike.intersects(r)) return true;
        }
    }
    return false;
}

bool solidAt(const TileMap& map, Vec2 p) { return map.solid(cellOf(p.x), cellOf(p.y)); }

}  // namespace pix::platformer
