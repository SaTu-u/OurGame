#include "gfx/pretty/Particles.hpp"

#include <algorithm>
#include <cmath>

namespace pix::gfx {

float Particles::random(float min, float max) {
    std::uniform_real_distribution<float> dist(min, max);
    return dist(rng_);
}

void Particles::burst(Vector2 at, const BurstParams& p) {
    for (int i = 0; i < p.count; ++i) {
        const float a = p.angle + random(-p.spread * 0.5f, p.spread * 0.5f);
        const float speed = random(p.speedMin, p.speedMax);
        Particle part;
        part.pos = at;
        part.vel = {std::cos(a) * speed, std::sin(a) * speed};
        part.maxLife = part.life = p.life * random(0.6f, 1.f);
        part.size = p.size * random(0.6f, 1.2f);
        part.endSize = 0.f;
        part.color = p.color;
        part.drag = p.drag;
        part.gravity = p.gravity;
        part.glow = p.glow;
        items_.push_back(part);
    }
}

void Particles::update(float dt) {
    for (Particle& p : items_) {
        p.vel.y += p.gravity * dt;
        const float k = std::max(0.f, 1.f - p.drag * dt);
        p.vel.x *= k;
        p.vel.y *= k;
        p.pos.x += p.vel.x * dt;
        p.pos.y += p.vel.y * dt;
        p.life -= dt;
    }
    std::erase_if(items_, [](const Particle& p) { return p.life <= 0.f; });
}

void Particles::draw(const Texture2D& glowTexture) const {
    const Rectangle src{0.f, 0.f, static_cast<float>(glowTexture.width), static_cast<float>(glowTexture.height)};

    // Сначала обычные частицы, потом светящиеся — одним «аддитивным» проходом.
    for (const Particle& p : items_) {
        if (p.glow) continue;
        const float t = p.life / p.maxLife;
        const float size = p.endSize + (p.size - p.endSize) * t;
        Color c = p.color;
        c.a = static_cast<unsigned char>(static_cast<float>(c.a) * std::min(1.f, t * 2.f));
        DrawRectanglePro({p.pos.x, p.pos.y, size, size}, {size * 0.5f, size * 0.5f}, p.life * 200.f, c);
    }

    BeginBlendMode(BLEND_ADDITIVE);
    for (const Particle& p : items_) {
        if (!p.glow) continue;
        const float t = p.life / p.maxLife;
        const float size = (p.endSize + (p.size - p.endSize) * t) * 3.f;
        Color c = p.color;
        c.a = static_cast<unsigned char>(static_cast<float>(c.a) * t);
        DrawTexturePro(glowTexture, src, {p.pos.x - size * 0.5f, p.pos.y - size * 0.5f, size, size}, {0.f, 0.f}, 0.f,
                       c);
    }
    EndBlendMode();
}

}  // namespace pix::gfx
