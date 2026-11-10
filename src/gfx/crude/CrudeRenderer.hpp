#pragma once

// Стадия 2 — «Стрёмное 2D».
// Специально кривая графика: разрешение 320x180, палитра из Paint, обводки мимо,
// облака едут не в ту сторону, текст в стиле WordArt и периодические глитчи.

#include <deque>
#include <random>
#include <string>
#include <vector>

#include "gfx/Gfx.hpp"
#include "gfx/PlatformerStage.hpp"

namespace pix::gfx {

class CrudeRenderer final : public PlatformerRenderer {
public:
    explicit CrudeRenderer(const GameContext& ctx);

    void beginLevel(const platformer::World& world, const platformer::Level& level) override;
    void onEvent(const platformer::WorldEvent& event, const platformer::World& world) override;
    void update(float dt, const platformer::World& world) override;
    void draw(const platformer::World& world, const StageView& view) override;

private:
    struct Debris {
        Vector2 pos;
        Vector2 vel;
        Color color;
        float life;
    };
    struct Popup {
        Vector2 pos;  ///< в пикселях мира
        std::string text;
        float life;
    };

    [[nodiscard]] Vector2 toScreen(platformer::Vec2 p) const;
    void burst(platformer::Vec2 at, int count, Color color);
    void popup(platformer::Vec2 at, std::string text);

    void drawBackground(int levelIndex) const;
    void drawTiles(const platformer::World& world) const;
    void drawEntities(const platformer::World& world) const;
    void drawPlayer(const platformer::World& world) const;
    void drawEffects() const;
    void drawHud(const platformer::World& world, const StageView& view) const;
    void drawDialogue(const Dialogue& dialogue) const;
    void drawPause(int selection) const;
    void drawWordArt(std::string_view text, Vector2 center, float size, float time) const;
    void present() const;

    const GameContext& ctx_;
    VirtualScreen screen_;
    Vector2 camera_{0.f, 0.f};
    float time_ = 0.f;
    float shake_ = 0.f;
    float glitchTimer_ = 4.f;
    float glitchActive_ = 0.f;
    mutable std::mt19937 rng_{1337};
    std::vector<Debris> debris_;
    std::vector<Popup> popups_;
    std::deque<platformer::Vec2> trail_;  ///< путь героя — по нему идут кенты
};

}  // namespace pix::gfx
