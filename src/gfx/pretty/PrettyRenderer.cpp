#include "gfx/pretty/PrettyRenderer.hpp"

#include <cmath>
#include <iterator>

namespace pix::gfx {

namespace {

using namespace platformer;

constexpr int kW = 1280;
constexpr int kH = 720;
constexpr float kTile = 48.f;
constexpr float kPi = 3.14159265f;

constexpr Color kPlayer{245, 247, 255, 255};
constexpr Color kPlayerGlow{120, 220, 255, 255};
constexpr Color kGlitch{110, 255, 150, 255};
constexpr Color kShader{255, 190, 90, 255};
constexpr Color kEnemy{70, 12, 36, 255};
constexpr Color kEnemyEye{255, 60, 90, 255};
constexpr Color kPixel{255, 228, 120, 255};
constexpr Color kPortal{160, 130, 255, 255};
constexpr Color kBoss{38, 38, 56, 255};
constexpr Color kBossVisor{255, 40, 70, 255};
constexpr Color kText{238, 240, 255, 255};
constexpr Color kTextDim{170, 168, 200, 255};

const PrettyPalette kPalettes[] = {
    // Неоновый закат
    {{10, 6, 32, 255}, {64, 20, 98, 255}, {255, 96, 140, 255},
     {255, 214, 102, 255}, {255, 64, 140, 255},
     {{92, 36, 128, 255}, {58, 22, 92, 255}, {32, 12, 58, 255}},
     {40, 28, 82, 255}, {20, 13, 46, 255}, {61, 242, 255, 255},
     {255, 70, 130, 255}},
    // Ядро системы — холоднее и тревожнее
    {{4, 6, 20, 255}, {16, 30, 66, 255}, {220, 50, 80, 255},
     {255, 120, 90, 255}, {200, 20, 70, 255},
     {{40, 40, 96, 255}, {24, 26, 66, 255}, {12, 14, 40, 255}},
     {30, 32, 70, 255}, {12, 12, 32, 255}, {255, 90, 120, 255},
     {255, 200, 80, 255}},
};

float hashNoise(float x, float seed) {
    // Сумма синусов — дешёвый и достаточно «природный» рельеф.
    return std::sin(x * 0.0021f + seed) * 0.5f + std::sin(x * 0.0057f + seed * 2.3f) * 0.3f +
           std::sin(x * 0.0131f + seed * 4.1f) * 0.2f;
}

Color speakerColor(const std::string& speaker) {
    if (speaker == "Пиксель") return kPlayerGlow;
    if (speaker == "Глюк") return kGlitch;
    if (speaker == "Шейдер") return kShader;
    if (speaker == "Сборщик Мусора") return kBossVisor;
    return kTextDim;
}

}  // namespace

PrettyRenderer::PrettyRenderer(const GameContext& ctx) : ctx_(ctx), screen_(kW, kH, false) {
    Image g = GenImageGradientRadial(128, 128, 0.f, WHITE, BLANK);
    glow_ = LoadTextureFromImage(g);
    UnloadImage(g);
    SetTextureFilter(glow_, TEXTURE_FILTER_BILINEAR);

    Image v = GenImageGradientRadial(256, 256, 0.55f, BLANK, Color{0, 0, 0, 150});
    vignette_ = LoadTextureFromImage(v);
    UnloadImage(v);
    SetTextureFilter(vignette_, TEXTURE_FILTER_BILINEAR);

    for (int i = 0; i < 140; ++i) {
        stars_.push_back({{particles_.random(0.f, kW), particles_.random(0.f, kH * 0.62f)},
                          particles_.random(0.f, 6.28f), particles_.random(1.f, 2.6f)});
    }
}

PrettyRenderer::~PrettyRenderer() {
    UnloadTexture(glow_);
    UnloadTexture(vignette_);
}

Vector2 PrettyRenderer::toPixels(Vec2 p) const { return {p.x * kTile, p.y * kTile}; }

void PrettyRenderer::glow(Vector2 center, float size, Color color) const {
    const Rectangle src{0.f, 0.f, static_cast<float>(glow_.width), static_cast<float>(glow_.height)};
    BeginBlendMode(BLEND_ADDITIVE);
    DrawTexturePro(glow_, src, {center.x - size * 0.5f, center.y - size * 0.5f, size, size}, {0.f, 0.f}, 0.f, color);
    EndBlendMode();
}

void PrettyRenderer::beginLevel(const World& world, const platformer::Level&) {
    const Vector2 c = toPixels(world.player().body.center());
    camera_ = {c.x - kW * 0.5f, c.y - kH * 0.58f};
    particles_.clear();
    texts_.clear();
    ghosts_.clear();
    trail_.clear();
    trauma_ = 0.f;
    flash_ = 0.f;
}

// ---------------------------------------------------------------- события → эффекты

void PrettyRenderer::onEvent(const WorldEvent& e, const World& world) {
    const Vector2 at = toPixels(e.pos);
    const Vector2 feet = toPixels({world.player().body.center().x, world.player().body.rect().bottom()});
    switch (e.type) {
        case EventType::Jump:
            particles_.burst(feet, {.count = 8, .color = {200, 220, 255, 160}, .speedMin = 40.f, .speedMax = 120.f,
                                    .life = 0.35f, .size = 8.f, .gravity = 200.f, .angle = kPi * 0.5f, .spread = 2.4f});
            break;
        case EventType::AirJump:
            particles_.burst(feet, {.count = 14, .color = withAlpha(kGlitch, 0.8f), .speedMin = 80.f,
                                    .speedMax = 200.f, .life = 0.4f, .size = 9.f, .angle = kPi * 0.5f, .spread = 2.f});
            break;
        case EventType::Land:
            squash_ = std::min(1.f, static_cast<float>(e.value) / 22.f);
            if (e.value > 8) {
                particles_.burst(feet, {.count = 10, .color = {200, 210, 255, 140}, .speedMin = 40.f,
                                        .speedMax = 160.f, .life = 0.35f, .size = 7.f, .gravity = 150.f,
                                        .angle = -kPi * 0.5f, .spread = 2.6f});
            }
            break;
        case EventType::Dash:
            addTrauma(0.15f);
            particles_.burst(at, {.count = 16, .color = withAlpha(kShader, 0.9f), .speedMin = 100.f,
                                  .speedMax = 300.f, .life = 0.35f, .size = 9.f,
                                  .angle = e.value > 0 ? kPi : 0.f, .spread = 1.2f});
            break;
        case EventType::Pickup:
            particles_.burst(at, {.count = 18, .color = kPixel, .speedMin = 80.f, .speedMax = 260.f, .life = 0.5f,
                                  .size = 8.f});
            texts_.push_back({{at.x, at.y - 20.f}, "+1", kPixel, 0.9f});
            break;
        case EventType::Stomp:
        case EventType::DashKill:
            addTrauma(0.3f);
            particles_.burst(at, {.count = 24, .color = kEnemyEye, .speedMin = 100.f, .speedMax = 340.f,
                                  .life = 0.6f, .size = 10.f, .gravity = 300.f});
            particles_.burst(at, {.count = 10, .color = {40, 10, 20, 255}, .speedMin = 60.f, .speedMax = 200.f,
                                  .life = 0.7f, .size = 7.f, .gravity = 500.f, .glow = false});
            break;
        case EventType::Hurt:
            addTrauma(0.5f);
            flash_ = 0.25f;
            particles_.burst(at, {.count = 16, .color = {255, 80, 110, 255}, .speedMin = 80.f, .speedMax = 260.f,
                                  .life = 0.5f, .size = 9.f});
            break;
        case EventType::Death:
            addTrauma(0.8f);
            particles_.burst(at, {.count = 60, .color = kPlayerGlow, .speedMin = 100.f, .speedMax = 520.f,
                                  .life = 1.f, .size = 12.f, .gravity = 120.f});
            break;
        case EventType::Respawn:
            particles_.burst(at, {.count = 30, .color = kPlayerGlow, .speedMin = 20.f, .speedMax = 160.f,
                                  .life = 0.6f, .size = 10.f});
            break;
        case EventType::Checkpoint:
            particles_.burst(at, {.count = 30, .color = palette_.edge, .speedMin = 60.f, .speedMax = 240.f,
                                  .life = 0.8f, .size = 9.f, .angle = -kPi * 0.5f, .spread = 1.6f});
            texts_.push_back({{at.x, at.y - 60.f}, "Чекпоинт", palette_.edge, 1.4f});
            break;
        case EventType::BuddyMet:
            flash_ = 0.35f;
            particles_.burst(at, {.count = 50, .color = e.value == 1 ? kGlitch : kShader, .speedMin = 60.f,
                                  .speedMax = 380.f, .life = 1.f, .size = 11.f});
            break;
        case EventType::LevelComplete:
            flash_ = 0.4f;
            particles_.burst(at, {.count = 70, .color = kPortal, .speedMin = 100.f, .speedMax = 500.f,
                                  .life = 1.1f, .size = 12.f});
            break;
        case EventType::BossShoot:
            particles_.burst(at, {.count = 6, .color = {255, 120, 60, 255}, .speedMin = 30.f, .speedMax = 90.f,
                                  .life = 0.3f, .size = 8.f});
            break;
        case EventType::BossSlam:
            addTrauma(0.7f);
            particles_.burst(at, {.count = 40, .color = {255, 150, 120, 200}, .speedMin = 120.f, .speedMax = 460.f,
                                  .life = 0.6f, .size = 10.f, .gravity = 400.f, .angle = -kPi * 0.5f,
                                  .spread = 2.8f});
            break;
        case EventType::BossHit:
            addTrauma(0.6f);
            flash_ = 0.3f;
            particles_.burst(at, {.count = 40, .color = kBossVisor, .speedMin = 100.f, .speedMax = 420.f,
                                  .life = 0.7f, .size = 11.f});
            break;
        case EventType::BossDefeated:
            addTrauma(1.f);
            flash_ = 1.f;
            particles_.burst(at, {.count = 160, .color = kBossVisor, .speedMin = 100.f, .speedMax = 900.f,
                                  .life = 1.6f, .size = 14.f, .drag = 1.2f});
            particles_.burst(at, {.count = 120, .color = kPlayerGlow, .speedMin = 50.f, .speedMax = 700.f,
                                  .life = 2.f, .size = 12.f, .drag = 1.f});
            break;
        case EventType::ProjectileBurst:
            particles_.burst(at, {.count = 10, .color = {255, 140, 70, 255}, .speedMin = 60.f, .speedMax = 200.f,
                                  .life = 0.4f, .size = 8.f, .gravity = 300.f});
            break;
        case EventType::BossAwake: addTrauma(0.4f); break;
    }
}

// ---------------------------------------------------------------- обновление

void PrettyRenderer::update(float dt, const World& world) {
    time_ += dt;
    flash_ = std::max(0.f, flash_ - dt * 1.6f);
    squash_ = std::max(0.f, squash_ - dt * 5.f);
    bossVisor_ += dt;

    // Камера плавно догоняет героя и немного смотрит вперёд по ходу движения.
    const Player& p = world.player();
    const Vector2 c = toPixels(p.body.center());
    lookAhead_ = damp(lookAhead_, static_cast<float>(p.facing) * 110.f, 2.f, dt);
    const Vector2 target{c.x + lookAhead_ - kW * 0.5f, c.y - kH * 0.58f};
    camera_.x = damp(camera_.x, target.x, 5.f, dt);
    camera_.y = damp(camera_.y, target.y, 4.f, dt);
    const float maxX = static_cast<float>(world.map().width()) * kTile - kW;
    const float maxY = static_cast<float>(world.map().height()) * kTile - kH;
    camera_.x = std::clamp(camera_.x, 0.f, std::max(0.f, maxX));
    camera_.y = std::clamp(camera_.y, std::min(0.f, maxY), std::max(0.f, maxY));

    // Тряска экрана: «травма» квадратично превращается в смещение и быстро гаснет.
    trauma_ = std::max(0.f, trauma_ - dt * 1.4f);
    const float shake = trauma_ * trauma_ * 22.f;
    shake_ = {std::sin(time_ * 47.f) * shake, std::cos(time_ * 39.f) * shake};

    blinkTimer_ -= dt;
    if (blinkTimer_ < -0.12f) blinkTimer_ = particles_.random(2.f, 5.f);

    // След рывка.
    if (p.dashing() && !p.dead) {
        const Rect r = p.body.rect();
        ghosts_.push_back({{r.x * kTile, r.y * kTile, r.w * kTile, r.h * kTile}, 0.25f});
        particles_.emit({c, {particles_.random(-40.f, 40.f), particles_.random(-40.f, 40.f)}, 0.4f, 0.4f, 10.f, 0.f,
                         withAlpha(kShader, 0.7f), 2.f, 0.f, true});
    }
    for (auto& g : ghosts_) g.life -= dt;
    std::erase_if(ghosts_, [](const Ghost& g) { return g.life <= 0.f; });

    // Портал всасывает искры.
    if (world.hasFinish() && particles_.random(0.f, 1.f) < dt * 30.f) {
        const Vector2 center = toPixels(world.finish().center());
        const float a = particles_.random(0.f, 6.28f);
        const float r = particles_.random(60.f, 110.f);
        particles_.emit({{center.x + std::cos(a) * r, center.y + std::sin(a) * r},
                         {-std::cos(a) * r * 1.6f, -std::sin(a) * r * 1.6f}, 0.6f, 0.6f, 7.f, 0.f,
                         withAlpha(kPortal, 0.8f), 0.f, 0.f, true});
    }
    // Пыль, летающая в воздухе, — для атмосферы.
    if (particles_.random(0.f, 1.f) < dt * 8.f) {
        particles_.emit({{camera_.x + particles_.random(0.f, kW), camera_.y + kH + 10.f},
                         {particles_.random(-10.f, 10.f), particles_.random(-40.f, -15.f)}, 6.f, 6.f,
                         particles_.random(3.f, 6.f), 0.f, withAlpha(palette_.edge, 0.35f), 0.f, 0.f, true});
    }
    particles_.update(dt);

    for (auto& t : texts_) {
        t.pos.y -= 40.f * dt;
        t.life -= dt;
    }
    std::erase_if(texts_, [](const FloatingText& t) { return t.life <= 0.f; });

    const Vec2 pc = p.body.center();
    if (trail_.empty() || distance(trail_.front(), pc) > 0.06f) trail_.push_front(pc);
    while (trail_.size() > 120) trail_.pop_back();
}

// ---------------------------------------------------------------- отрисовка мира

void PrettyRenderer::draw(const World& world, const StageView& view) {
    palette_ = kPalettes[std::min<std::size_t>(static_cast<std::size_t>(view.levelIndex), std::size(kPalettes) - 1)];

    screen_.begin();
    drawSky(time_);
    drawMountains();

    Camera2D cam{};
    cam.target = {std::round(camera_.x + shake_.x), std::round(camera_.y + shake_.y)};
    cam.zoom = 1.f;
    BeginMode2D(cam);
    drawTiles(world);
    drawFinish(world);
    drawCheckpoints(world);
    drawPickups(world);
    drawBuddies(world);
    drawEnemies(world);
    drawProjectiles(world);
    drawBoss(world);
    drawFollowers(world);
    drawPlayer(world);
    particles_.draw(glow_);
    for (const auto& t : texts_) {
        const float a = std::min(1.f, t.life * 2.f);
        drawTextCentered(ctx_.fonts->ui, t.text, t.pos, 26.f, 1.f, withAlpha(t.color, a));
    }
    EndMode2D();

    DrawTexturePro(vignette_, {0.f, 0.f, 256.f, 256.f}, {0.f, 0.f, kW, kH}, {0.f, 0.f}, 0.f, WHITE);
    if (!view.dialogue) {
        drawHud(world, view);
        drawBossBar(world);
    }
    drawLevelTitle(view);
    if (flash_ > 0.f) DrawRectangle(0, 0, kW, kH, withAlpha(WHITE, flash_ * 0.6f));
    if (view.dialogue && !view.dialogue->finished()) drawDialogue(*view.dialogue);
    if (view.paused) drawPause(view.pauseSelection);
    if (view.fade > 0.f) DrawRectangle(0, 0, kW, kH, withAlpha(BLACK, view.fade));
    screen_.end();

    ClearBackground(BLACK);
    screen_.present();
}

void PrettyRenderer::drawSky(float time) const {
    const PrettyPalette& pal = palette_;
    DrawRectangleGradientV(0, 0, kW, kH / 2, pal.skyTop, pal.skyMid);
    DrawRectangleGradientV(0, kH / 2, kW, kH / 2, pal.skyMid, pal.horizon);

    for (const Star& s : stars_) {
        const float twinkle = 0.45f + 0.55f * std::sin(time * 1.7f + s.phase);
        const float x = std::fmod(s.pos.x - camera_.x * 0.02f + kW * 10.f, static_cast<float>(kW));
        DrawCircleV({x, s.pos.y}, s.size, withAlpha(WHITE, twinkle * 0.8f));
    }

    // Солнце в стиле синтвейв: вертикальный градиент и прорези, которые ползут вниз.
    const Vector2 sun{kW * 0.68f - camera_.x * 0.03f, kH * 0.55f};
    const float radius = 150.f;
    glow(sun, radius * 4.2f, withAlpha(pal.sunBottom, 0.45f));
    for (float dy = -radius; dy < radius; dy += 2.f) {
        const float t = (dy + radius) / (radius * 2.f);
        if (dy > 0.f) {
            const float band = std::fmod(dy + time * 12.f, 26.f);
            if (band < 3.f + t * 9.f) continue;
        }
        const float half = std::sqrt(radius * radius - dy * dy);
        DrawRectangle(static_cast<int>(sun.x - half), static_cast<int>(sun.y + dy), static_cast<int>(half * 2.f), 2,
                      mix(pal.sunTop, pal.sunBottom, t));
    }
}

void PrettyRenderer::drawMountains() const {
    const float parallax[3] = {0.08f, 0.2f, 0.38f};
    const float baseY[3] = {kH * 0.62f, kH * 0.7f, kH * 0.8f};
    const float amp[3] = {120.f, 110.f, 90.f};
    constexpr float step = 8.f;
    for (int layer = 0; layer < 3; ++layer) {
        const Color c = palette_.mountains[layer];
        const float shift = camera_.x * parallax[layer];
        const float vshift = camera_.y * parallax[layer] * 0.3f;
        float prevTop = 0.f;
        for (float x = 0.f; x <= kW + step; x += step) {
            const float top = baseY[layer] - vshift - amp[layer] * hashNoise(x + shift, static_cast<float>(layer) * 7.3f);
            if (x > 0.f) {
                // Два треугольника на каждый кусок склона (вершины — против часовой стрелки).
                DrawTriangle({x - step, prevTop}, {x - step, kH}, {x, kH}, c);
                DrawTriangle({x - step, prevTop}, {x, kH}, {x, top}, c);
            }
            prevTop = top;
        }
    }
    // Дымка у горизонта.
    DrawRectangleGradientV(0, static_cast<int>(kH * 0.55f), kW, static_cast<int>(kH * 0.45f), BLANK,
                           withAlpha(palette_.horizon, 0.18f));
}

void PrettyRenderer::drawTiles(const World& world) const {
    const TileMap& map = world.map();
    const int x0 = std::max(0, static_cast<int>(camera_.x / kTile) - 1);
    const int x1 = std::min(map.width() - 1, static_cast<int>((camera_.x + kW) / kTile) + 1);
    const int y0 = std::max(0, static_cast<int>(camera_.y / kTile) - 1);
    const int y1 = std::min(map.height() - 1, static_cast<int>((camera_.y + kH) / kTile) + 1);
    const PrettyPalette& pal = palette_;

    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const float px = static_cast<float>(x) * kTile;
            const float py = static_cast<float>(y) * kTile;
            const Tile tile = map.at(x, y);
            if (tile == Tile::Solid) {
                const bool airAbove = !map.solid(x, y - 1);
                // Чем глубже блок, тем он темнее.
                int depth = 0;
                while (depth < 3 && map.solid(x, y - depth - 1)) ++depth;
                const Color base = mix(pal.tile, pal.tileDeep, static_cast<float>(depth) / 3.f);
                DrawRectangle(static_cast<int>(px), static_cast<int>(py), static_cast<int>(kTile),
                              static_cast<int>(kTile), base);
                if (airAbove) {
                    DrawRectangleGradientV(static_cast<int>(px), static_cast<int>(py), static_cast<int>(kTile), 18,
                                           withAlpha(pal.edge, 0.22f), BLANK);
                    DrawRectangle(static_cast<int>(px), static_cast<int>(py), static_cast<int>(kTile), 3, pal.edge);
                }
                if (!map.solid(x - 1, y)) {
                    DrawRectangle(static_cast<int>(px), static_cast<int>(py), 2, static_cast<int>(kTile),
                                  withAlpha(pal.edge, airAbove ? 0.5f : 0.25f));
                }
                if (!map.solid(x + 1, y)) {
                    DrawRectangle(static_cast<int>(px + kTile - 2.f), static_cast<int>(py), 2, static_cast<int>(kTile),
                                  withAlpha(pal.edge, airAbove ? 0.5f : 0.25f));
                }
                // Мелкий «узор» на поверхности, чтобы блоки не выглядели плоско.
                if (((x * 7 + y * 13) % 5) == 0) {
                    DrawRectangleRounded({px + 10.f, py + 16.f, 6.f, 6.f}, 0.5f, 4, withAlpha(pal.edge, 0.12f));
                }
            } else if (tile == Tile::Spike) {
                for (int k = 0; k < 3; ++k) {
                    const float sx = px + 4.f + static_cast<float>(k) * 14.f;
                    DrawTriangle({sx, py + kTile}, {sx + 13.f, py + kTile}, {sx + 6.5f, py + kTile * 0.42f}, pal.spike);
                }
            }
        }
    }
    // Свечение под шипами — отдельным проходом, чтобы не переключать режим смешивания на каждом тайле.
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            if (map.at(x, y) == Tile::Spike) {
                glow({static_cast<float>(x) * kTile + kTile * 0.5f, static_cast<float>(y) * kTile + kTile * 0.85f},
                     110.f, withAlpha(pal.spike, 0.35f));
            }
        }
    }
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            if (map.solid(x, y) && !map.solid(x, y - 1)) {
                glow({static_cast<float>(x) * kTile + kTile * 0.5f, static_cast<float>(y) * kTile}, 90.f,
                     withAlpha(pal.edge, 0.10f));
            }
        }
    }
}

void PrettyRenderer::drawFinish(const World& world) const {
    if (!world.hasFinish()) return;
    const Vector2 c = toPixels(world.finish().center());
    glow(c, 360.f, withAlpha(kPortal, 0.55f));
    for (int i = 0; i < 3; ++i) {
        const float r = 40.f + static_cast<float>(i) * 14.f;
        const float start = time_ * (90.f + static_cast<float>(i) * 50.f) * (i % 2 ? -1.f : 1.f);
        DrawRing(c, r, r + 4.f, start, start + 250.f, 48, withAlpha(kPortal, 0.9f - static_cast<float>(i) * 0.25f));
    }
    DrawCircleV(c, 26.f + std::sin(time_ * 4.f) * 3.f, withAlpha(WHITE, 0.85f));
}

void PrettyRenderer::drawCheckpoints(const World& world) const {
    for (const Checkpoint& cp : world.checkpoints()) {
        const Vector2 base = toPixels({cp.area.center().x, cp.area.bottom()});
        const Color c = cp.active ? palette_.edge : Color{120, 100, 170, 255};
        DrawRectangleRounded({base.x - 3.f, base.y - 70.f, 6.f, 70.f}, 1.f, 6, withAlpha(c, 0.6f));
        const float bob = std::sin(time_ * 2.5f) * 4.f;
        DrawPoly({base.x, base.y - 84.f + bob}, 4, 13.f, time_ * 60.f, c);
        if (cp.active) glow({base.x, base.y - 84.f + bob}, 160.f, withAlpha(c, 0.6f));
    }
}

void PrettyRenderer::drawPickups(const World& world) const {
    for (const Pickup& p : world.pickups()) {
        if (p.taken) continue;
        const Vector2 c = toPixels(p.pos);
        const float bob = std::sin(time_ * 3.f + p.pos.x) * 6.f;
        const Vector2 at{c.x, c.y + bob};
        glow(at, 100.f, withAlpha(kPixel, 0.55f));
        DrawPoly(at, 4, 11.f, time_ * 90.f + p.pos.x * 40.f, kPixel);
        DrawPoly(at, 4, 5.f, time_ * 90.f + p.pos.x * 40.f, WHITE);
    }
}

void PrettyRenderer::drawEnemies(const World& world) const {
    for (const Enemy& e : world.enemies()) {
        if (!e.alive) continue;
        const Rect r = e.body.rect();
        const Rectangle body{r.x * kTile, r.y * kTile, r.w * kTile, r.h * kTile};
        // Лапки шевелятся.
        for (int i = 0; i < 3; ++i) {
            const float lx = body.x + 7.f + static_cast<float>(i) * (body.width - 14.f) * 0.5f;
            const float swing = std::sin(time_ * 18.f + static_cast<float>(i) * 2.f) * 4.f;
            DrawLineEx({lx, body.y + body.height - 4.f}, {lx + swing, body.y + body.height + 3.f}, 3.f, kEnemy);
        }
        const float squish = 1.f + std::sin(time_ * 12.f + r.x) * 0.05f;
        const Rectangle shell{body.x, body.y + body.height * (1.f - squish), body.width, body.height * squish};
        DrawRectangleRounded(shell, 0.6f, 8, kEnemy);
        DrawRectangleRounded({shell.x + 4.f, shell.y + 3.f, shell.width - 8.f, 5.f}, 1.f, 6, {110, 30, 60, 255});
        const float eyeX = e.dir > 0.f ? body.x + body.width - 13.f : body.x + 7.f;
        for (int i = 0; i < 2; ++i) {
            const Vector2 eye{eyeX + static_cast<float>(i) * 7.f, body.y + 12.f};
            glow(eye, 34.f, withAlpha(kEnemyEye, 0.8f));
            DrawCircleV(eye, 2.6f, kEnemyEye);
        }
    }
}

void PrettyRenderer::drawBuddies(const World& world) const {
    for (const Buddy& b : world.buddies()) {
        if (b.met) continue;
        const Color c = b.id == 1 ? kGlitch : kShader;
        const Vector2 base = toPixels(b.pos);
        const float bob = std::sin(time_ * 3.f) * 5.f;
        const Rectangle body{base.x - 15.f, base.y - 34.f + bob, 30.f, 30.f};
        glow({base.x, body.y + 15.f}, 170.f, withAlpha(c, 0.55f));
        if (b.id == 2) {
            // Шейдер — источник света: вокруг него крутятся лучи.
            BeginBlendMode(BLEND_ADDITIVE);
            for (int i = 0; i < 6; ++i) {
                const float a = time_ * 0.8f + static_cast<float>(i) * kPi / 3.f;
                const Vector2 o{base.x, body.y + 15.f};
                DrawTriangle(o, {o.x + std::cos(a + 0.08f) * 120.f, o.y + std::sin(a + 0.08f) * 120.f},
                             {o.x + std::cos(a - 0.08f) * 120.f, o.y + std::sin(a - 0.08f) * 120.f},
                             withAlpha(c, 0.12f));
            }
            EndBlendMode();
        } else if (static_cast<int>(time_ * 5.f) % 7 == 0) {
            // Глюк иногда «двоится» по цветовым каналам.
            DrawRectangleRounded({body.x - 4.f, body.y, body.width, body.height}, 0.3f, 6, withAlpha(RED, 0.5f));
            DrawRectangleRounded({body.x + 4.f, body.y, body.width, body.height}, 0.3f, 6, withAlpha(BLUE, 0.5f));
        }
        DrawRectangleRounded(body, 0.3f, 6, c);
        DrawRectangleRounded({body.x + 8.f, body.y + 9.f, 4.f, 8.f}, 1.f, 4, {20, 20, 30, 255});
        DrawRectangleRounded({body.x + 18.f, body.y + 9.f, 4.f, 8.f}, 1.f, 4, {20, 20, 30, 255});
        drawTextCentered(ctx_.fonts->title, "!", {base.x, body.y - 44.f + bob * 0.5f}, 36.f, 0.f, c);
    }
}

void PrettyRenderer::drawProjectiles(const World& world) const {
    for (const Projectile& shot : world.projectiles()) {
        const Vector2 c = toPixels(shot.body.center());
        glow(c, 80.f, {255, 120, 60, 200});
        DrawPoly(c, 4, 12.f, time_ * 400.f, {255, 150, 80, 255});
    }
}

void PrettyRenderer::drawBoss(const World& world) const {
    const Boss& boss = world.boss();
    if (!boss.present || boss.state == BossState::Dead) return;
    const Rect r = boss.body.rect();
    Rectangle body{r.x * kTile, r.y * kTile, r.w * kTile, r.h * kTile};
    if (boss.state == BossState::Telegraph) body.x += std::sin(time_ * 80.f) * 4.f;  // дрожит перед ударом

    const bool stunned = boss.state == BossState::Stunned;
    const Vector2 center{body.x + body.width * 0.5f, body.y + body.height * 0.5f};
    glow(center, 420.f, withAlpha(kBossVisor, stunned ? 0.15f : 0.35f));
    if (boss.state == BossState::Hover || boss.state == BossState::Rise) {
        // Двигатели снизу.
        for (int i = 0; i < 2; ++i) {
            const Vector2 jet{body.x + body.width * (0.25f + 0.5f * static_cast<float>(i)), body.y + body.height};
            glow(jet, 70.f + std::sin(time_ * 30.f) * 10.f, {255, 140, 80, 220});
        }
    }
    DrawRectangleRounded(body, 0.25f, 10, kBoss);
    DrawRectangleRoundedLinesEx(body, 0.25f, 10, 3.f, withAlpha(kBossVisor, 0.6f));
    // Крышка «мусорного бака».
    DrawRectangleRounded({body.x - 8.f, body.y - 12.f, body.width + 16.f, 16.f}, 0.6f, 8, {58, 58, 80, 255});

    // Визор сканирует пространство слева направо.
    const float visorW = body.width - 30.f;
    const Rectangle visor{body.x + 15.f, body.y + 26.f, visorW, 16.f};
    DrawRectangleRounded(visor, 1.f, 8, {20, 6, 14, 255});
    if (!stunned || static_cast<int>(time_ * 12.f) % 2 == 0) {
        const float scan = (std::sin(bossVisor_ * (boss.state == BossState::Telegraph ? 14.f : 3.f)) * 0.5f + 0.5f);
        const Vector2 eye{visor.x + 10.f + scan * (visor.width - 20.f), visor.y + visor.height * 0.5f};
        glow(eye, 120.f, kBossVisor);
        DrawCircleV(eye, 7.f, {255, 200, 210, 255});
    }
    // Полоски-«рёбра» бака.
    for (int i = 0; i < 4; ++i) {
        const float x = body.x + 18.f + static_cast<float>(i) * (body.width - 36.f) / 3.f;
        DrawRectangleRounded({x - 3.f, body.y + 54.f, 6.f, body.height - 64.f}, 1.f, 4, {55, 55, 78, 255});
    }
    if (stunned) {
        // Звёздочки над головой оглушённого босса.
        for (int i = 0; i < 4; ++i) {
            const float a = time_ * 4.f + static_cast<float>(i) * kPi * 0.5f;
            const Vector2 s{center.x + std::cos(a) * 50.f, body.y - 26.f + std::sin(a) * 10.f};
            glow(s, 40.f, kPixel);
            DrawPoly(s, 4, 5.f, time_ * 200.f, kPixel);
        }
    }
    if (boss.hurtFlash > 0.f) DrawRectangleRounded(body, 0.25f, 10, withAlpha(WHITE, boss.hurtFlash * 2.f));
}

void PrettyRenderer::drawFollowers(const World& world) const {
    int index = 0;
    const auto follower = [&](Color color, bool glitchy) {
        const std::size_t delay = static_cast<std::size_t>(16 + index * 16);
        ++index;
        if (trail_.size() <= delay) return;
        const Vector2 c = toPixels(trail_[delay]);
        const float bob = std::sin(time_ * 4.f + static_cast<float>(index)) * 4.f;
        const Rectangle body{c.x - 11.f, c.y - 11.f + bob, 22.f, 22.f};
        glow({c.x, c.y + bob}, 110.f, withAlpha(color, 0.5f));
        if (glitchy && static_cast<int>(time_ * 6.f) % 9 == 0) {
            DrawRectangleRounded({body.x - 3.f, body.y, body.width, body.height}, 0.35f, 6, withAlpha(RED, 0.5f));
            DrawRectangleRounded({body.x + 3.f, body.y, body.width, body.height}, 0.35f, 6, withAlpha(BLUE, 0.5f));
        }
        DrawRectangleRounded(body, 0.35f, 6, color);
        DrawRectangleRounded({body.x + 5.f, body.y + 6.f, 3.f, 6.f}, 1.f, 4, {20, 20, 30, 255});
        DrawRectangleRounded({body.x + 13.f, body.y + 6.f, 3.f, 6.f}, 1.f, 4, {20, 20, 30, 255});
    };
    if (world.abilities().doubleJump) follower(kGlitch, true);
    if (world.abilities().dash) follower(kShader, false);
}

void PrettyRenderer::drawPlayer(const World& world) const {
    const Player& p = world.player();
    for (const Ghost& g : ghosts_) {
        DrawRectangleRounded(g.rect, 0.35f, 8, withAlpha(kShader, g.life * 1.6f));
    }
    if (p.dead) return;

    const Rect r = p.body.rect();
    const Vector2 feet{(r.x + r.w * 0.5f) * kTile, r.bottom() * kTile};
    const float size = r.w * kTile;

    // Сжатие и растяжение: в прыжке вытягивается, при приземлении сплющивается.
    float sy = 1.f + std::min(0.22f, std::abs(p.body.vel.y) / 90.f);
    if (p.body.onGround) sy = 1.f;
    sy -= squash_ * 0.3f;
    const float sx = 1.f / sy;
    const Rectangle body{feet.x - size * sx * 0.5f, feet.y - size * sy, size * sx, size * sy};

    const float alpha = p.invuln > 0.f ? (std::sin(time_ * 40.f) > 0.f ? 0.35f : 1.f) : 1.f;
    glow({feet.x, feet.y - size * 0.5f}, 190.f, withAlpha(kPlayerGlow, 0.55f * alpha));
    DrawRectangleRounded(body, 0.35f, 8, withAlpha(kPlayer, alpha));
    DrawRectangleRounded({body.x + 4.f, body.y + 3.f, body.width - 8.f, body.height * 0.3f}, 0.6f, 6,
                         withAlpha(WHITE, 0.5f * alpha));

    // Глаза смотрят туда, куда идём, и иногда моргают.
    const float look = static_cast<float>(p.facing) * 4.f + std::clamp(p.body.vel.x * 0.25f, -3.f, 3.f);
    const float lookY = std::clamp(p.body.vel.y * 0.15f, -3.f, 3.f);
    const float eyeH = blinkTimer_ < 0.f ? 2.f : 10.f;
    for (int i = 0; i < 2; ++i) {
        const float ex = body.x + body.width * (0.32f + 0.36f * static_cast<float>(i)) + look - 3.f;
        DrawRectangleRounded({ex, body.y + body.height * 0.38f + lookY + (10.f - eyeH) * 0.5f, 6.f, eyeH}, 1.f, 4,
                             withAlpha({24, 22, 40, 255}, alpha));
    }
}

// ---------------------------------------------------------------- интерфейс

void PrettyRenderer::drawHud(const World& world, const StageView& view) const {
    const Player& p = world.player();
    for (int i = 0; i < p.maxHp; ++i) {
        const Rectangle pip{28.f + static_cast<float>(i) * 40.f, 26.f, 28.f, 28.f};
        if (i < p.hp) {
            glow({pip.x + 14.f, pip.y + 14.f}, 80.f, withAlpha(kPlayerGlow, 0.5f));
            DrawRectangleRounded(pip, 0.35f, 6, kPlayer);
        } else {
            DrawRectangleRoundedLinesEx(pip, 0.35f, 6, 2.f, withAlpha(kTextDim, 0.6f));
        }
    }

    DrawPoly({40.f, 86.f}, 4, 10.f, time_ * 60.f, kPixel);
    const std::string pixels =
        std::to_string(world.pickupsCollected()) + " / " + std::to_string(world.pickups().size());
    DrawTextEx(ctx_.fonts->ui, pixels.c_str(), {60.f, 72.f}, 28.f, 1.f, kText);

    const std::string level = "Уровень " + std::to_string(view.levelIndex + 1) + " · " + std::string(view.levelTitle);
    DrawTextEx(ctx_.fonts->ui, level.c_str(), {kW - 28.f - textWidth(ctx_.fonts->ui, level, 22.f, 1.f), 28.f}, 22.f,
               1.f, withAlpha(kText, 0.65f));

    // Способности — «чипы» внизу экрана.
    float x = 28.f;
    const auto chip = [&](const char* text, Color color, float cooldown) {
        const float w = textWidth(ctx_.fonts->ui, text, 20.f, 1.f) + 36.f;
        const Rectangle box{x, kH - 54.f, w, 32.f};
        DrawRectangleRounded(box, 1.f, 10, withAlpha({10, 8, 30, 255}, 0.7f));
        DrawRectangleRoundedLinesEx(box, 1.f, 10, 1.5f, withAlpha(color, 0.8f));
        DrawCircleV({box.x + 16.f, box.y + 16.f}, 5.f, cooldown > 0.f ? withAlpha(color, 0.3f) : color);
        DrawTextEx(ctx_.fonts->ui, text, {box.x + 28.f, box.y + 6.f}, 20.f, 1.f, kText);
        x += w + 12.f;
    };
    if (world.abilities().doubleJump) chip("Двойной прыжок", kGlitch, 0.f);
    if (world.abilities().dash) chip("Рывок · Shift", kShader, p.dashCooldown);
}

void PrettyRenderer::drawBossBar(const World& world) const {
    const Boss& boss = world.boss();
    if (!boss.present || boss.state == BossState::Dormant || boss.state == BossState::Dead) return;
    const float w = 520.f;
    const float x = (kW - w) * 0.5f;
    drawTextCentered(ctx_.fonts->ui, "СБОРЩИК МУСОРА", {kW * 0.5f, 20.f}, 22.f, 4.f, kBossVisor);
    for (int i = 0; i < boss.maxHp; ++i) {
        const float segW = (w - static_cast<float>(boss.maxHp - 1) * 8.f) / static_cast<float>(boss.maxHp);
        const Rectangle seg{x + static_cast<float>(i) * (segW + 8.f), 52.f, segW, 12.f};
        if (i < boss.hp) {
            DrawRectangleRounded(seg, 1.f, 6, kBossVisor);
        } else {
            DrawRectangleRoundedLinesEx(seg, 1.f, 6, 1.5f, withAlpha(kBossVisor, 0.4f));
        }
    }
}

void PrettyRenderer::drawLevelTitle(const StageView& view) const {
    // Название уровня проявляется и тает за первые секунды.
    const float t = view.levelTime;
    if (t > 3.5f || view.dialogue) return;
    const float alpha = t < 0.6f ? t / 0.6f : (t > 2.6f ? 1.f - (t - 2.6f) / 0.9f : 1.f);
    const float rise = (1.f - easeOutCubic(t / 0.8f)) * 20.f;
    drawTextCentered(ctx_.fonts->ui, "СТАДИЯ 3 · КРАСИВОЕ 2D", {kW * 0.5f, kH * 0.32f + rise}, 22.f, 6.f,
                     withAlpha(palette_.edge, alpha));
    drawTextCentered(ctx_.fonts->title, view.levelTitle, {kW * 0.5f, kH * 0.36f + rise}, 72.f, 2.f,
                     withAlpha(kText, alpha));
}

void PrettyRenderer::drawDialogue(const Dialogue& dialogue) const {
    const DialogueLine& line = dialogue.current();
    const bool narrator = line.speaker.empty();
    const Color accent = speakerColor(line.speaker);

    // Окно диалога сверху — внизу земля и герой, их не закрываем.
    const Rectangle box{140.f, 36.f, kW - 280.f, 172.f};
    DrawRectangleRounded(box, 0.18f, 12, withAlpha({10, 8, 30, 255}, 0.86f));
    DrawRectangleRoundedLinesEx(box, 0.18f, 12, 2.f, withAlpha(accent, 0.7f));

    float textX = box.x + 36.f;
    if (!narrator) {
        // «Портрет» персонажа — светящийся квадрат с глазами.
        const Rectangle portrait{box.x + 28.f, box.y + 36.f, 96.f, 96.f};
        glow({portrait.x + 48.f, portrait.y + 48.f}, 220.f, withAlpha(accent, 0.45f));
        DrawRectangleRounded(portrait, 0.3f, 8, accent);
        DrawRectangleRounded({portrait.x + 26.f, portrait.y + 30.f, 12.f, 26.f}, 1.f, 4, {20, 20, 30, 255});
        DrawRectangleRounded({portrait.x + 58.f, portrait.y + 30.f, 12.f, 26.f}, 1.f, 4, {20, 20, 30, 255});
        textX = portrait.x + portrait.width + 32.f;
        DrawTextEx(ctx_.fonts->ui, line.speaker.c_str(), {textX, box.y + 22.f}, 28.f, 1.f, accent);
    }
    const float maxWidth = box.x + box.width - 40.f - textX;
    float y = box.y + (narrator ? 34.f : 62.f);
    for (const auto& row : wrapText(ctx_.fonts->ui, dialogue.visibleText(), 28.f, 1.f, maxWidth)) {
        DrawTextEx(ctx_.fonts->ui, row.c_str(), {textX, y}, 28.f, 1.f, narrator ? withAlpha(kText, 0.85f) : kText);
        y += 34.f;
    }
    if (dialogue.lineFullyShown()) {
        const float pulse = 0.5f + 0.5f * std::sin(time_ * 5.f);
        DrawTextEx(ctx_.fonts->ui, "Enter →", {box.x + box.width - 120.f, box.y + box.height - 40.f}, 22.f, 1.f,
                   withAlpha(accent, 0.5f + 0.5f * pulse));
    }
}

void PrettyRenderer::drawPause(int selection) const {
    DrawRectangle(0, 0, kW, kH, withAlpha({6, 4, 20, 255}, 0.7f));
    drawTextCentered(ctx_.fonts->title, "Пауза", {kW * 0.5f, kH * 0.3f}, 80.f, 2.f, kText);
    const char* items[] = {"Продолжить", "Выйти (прогресс сохранён)"};
    for (int i = 0; i < 2; ++i) {
        const float y = kH * 0.5f + static_cast<float>(i) * 64.f;
        const bool selected = i == selection;
        if (selected) {
            const float w = textWidth(ctx_.fonts->ui, items[i], 32.f, 1.f) + 60.f;
            DrawRectangleRounded({kW * 0.5f - w * 0.5f, y - 8.f, w, 52.f}, 1.f, 12, withAlpha(palette_.edge, 0.2f));
        }
        drawTextCentered(ctx_.fonts->ui, items[i], {kW * 0.5f, y}, 32.f, 1.f, selected ? palette_.edge : kTextDim);
    }
}

}  // namespace pix::gfx
