#pragma once

// Стадия 3 — «Красивое 2D».
// Тот же самый мир, что и в стрёмном 2D, но: 1280x720, неоновый закат,
// параллакс гор, частицы, свечение, плавная камера с тряской, сжатие-растяжение героя.

#include <deque>
#include <string>
#include <vector>

#include "gfx/Gfx.hpp"
#include "gfx/PlatformerStage.hpp"
#include "gfx/pretty/Particles.hpp"

namespace pix::gfx {

/// Цвета уровня. У каждого уровня своя палитра.
struct PrettyPalette {
    Color skyTop, skyMid, horizon;
    Color sunTop, sunBottom;
    Color mountains[3];
    Color tile, tileDeep, edge;
    Color spike;
};

class PrettyRenderer final : public PlatformerRenderer {
public:
    explicit PrettyRenderer(const GameContext& ctx);
    ~PrettyRenderer() override;

    void beginLevel(const platformer::World& world, const platformer::Level& level) override;
    void onEvent(const platformer::WorldEvent& event, const platformer::World& world) override;
    void update(float dt, const platformer::World& world) override;
    void draw(const platformer::World& world, const StageView& view) override;

private:
    struct Star {
        Vector2 pos;
        float phase;
        float size;
    };
    struct FloatingText {
        Vector2 pos;
        std::string text;
        Color color;
        float life;
    };
    struct Ghost {  ///< «отпечаток» героя во время рывка
        Rectangle rect;
        float life;
    };

    [[nodiscard]] Vector2 toPixels(platformer::Vec2 p) const;
    void addTrauma(float amount) { trauma_ = std::min(1.f, trauma_ + amount); }
    void glow(Vector2 center, float size, Color color) const;

    void drawSky(float time) const;
    void drawMountains() const;
    void drawTiles(const platformer::World& world) const;
    void drawFinish(const platformer::World& world) const;
    void drawCheckpoints(const platformer::World& world) const;
    void drawPickups(const platformer::World& world) const;
    void drawEnemies(const platformer::World& world) const;
    void drawBuddies(const platformer::World& world) const;
    void drawBoss(const platformer::World& world) const;
    void drawProjectiles(const platformer::World& world) const;
    void drawFollowers(const platformer::World& world) const;
    void drawPlayer(const platformer::World& world) const;
    void drawHud(const platformer::World& world, const StageView& view) const;
    void drawBossBar(const platformer::World& world) const;
    void drawLevelTitle(const StageView& view) const;
    void drawDialogue(const Dialogue& dialogue) const;
    void drawPause(int selection) const;

    const GameContext& ctx_;
    VirtualScreen screen_;
    Texture2D glow_{};
    Texture2D vignette_{};
    PrettyPalette palette_{};

    Vector2 camera_{0.f, 0.f};  ///< левый верхний угол в пикселях мира
    float lookAhead_ = 0.f;
    float trauma_ = 0.f;
    Vector2 shake_{0.f, 0.f};
    float time_ = 0.f;
    float flash_ = 0.f;

    float squash_ = 0.f;  ///< >0 — героя сплющило при приземлении
    float blinkTimer_ = 3.f;
    float bossVisor_ = 0.f;

    Particles particles_;
    std::vector<Star> stars_;
    std::vector<FloatingText> texts_;
    std::vector<Ghost> ghosts_;
    std::deque<platformer::Vec2> trail_;
};

}  // namespace pix::gfx
