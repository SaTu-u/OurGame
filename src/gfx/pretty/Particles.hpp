#pragma once

// Система частиц для красивого 2D: искры, пыль, следы рывка, взрывы.
// Светящиеся частицы рисуются с аддитивным смешиванием — так получается «неон».

#include <raylib.h>

#include <random>
#include <vector>

namespace pix::gfx {

struct Particle {
    Vector2 pos{};
    Vector2 vel{};
    float life = 1.f;
    float maxLife = 1.f;
    float size = 8.f;
    float endSize = 0.f;
    Color color = WHITE;
    float drag = 0.f;     ///< торможение (доля скорости в секунду)
    float gravity = 0.f;  ///< пикселей/с²
    bool glow = true;
};

struct BurstParams {
    int count = 12;
    Color color = WHITE;
    float speedMin = 60.f;
    float speedMax = 240.f;
    float life = 0.6f;
    float size = 10.f;
    float gravity = 0.f;
    float drag = 2.f;
    float angle = 0.f;        ///< направление (радианы)
    float spread = 6.2832f;   ///< разброс вокруг направления; 2π — во все стороны
    bool glow = true;
};

class Particles {
public:
    void emit(const Particle& p) { items_.push_back(p); }
    void burst(Vector2 at, const BurstParams& params);
    void update(float dt);
    /// glowTexture — мягкое белое пятно, которое растягивается и подкрашивается.
    void draw(const Texture2D& glowTexture) const;
    void clear() { items_.clear(); }
    [[nodiscard]] std::size_t size() const { return items_.size(); }

    /// Случайное число в диапазоне — пригодится и снаружи.
    float random(float min, float max);

private:
    std::vector<Particle> items_;
    std::mt19937 rng_{2024};
};

}  // namespace pix::gfx
