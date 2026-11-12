#include "gfx/crude/CrudeRenderer.hpp"

#include <cmath>
#include <iterator>

#include "core/Utf8.hpp"

namespace pix::gfx {

namespace {

using namespace platformer;

constexpr int kW = 320;
constexpr int kH = 180;
constexpr float kTile = 10.f;  ///< пикселей на клетку

// Палитра «как в Paint».
constexpr Color kPaintBlue{0, 162, 232, 255};
constexpr Color kPaintGreen{34, 177, 76, 255};
constexpr Color kLime{181, 230, 29, 255};
constexpr Color kBrown{185, 122, 87, 255};
constexpr Color kPaintRed{237, 28, 36, 255};
constexpr Color kPaintYellow{255, 242, 0, 255};
constexpr Color kPaintOrange{255, 127, 39, 255};
constexpr Color kPaintPurple{163, 73, 164, 255};
constexpr Color kWin98Gray{192, 192, 192, 255};
constexpr Color kWordArt[] = {kPaintRed, kPaintOrange, kPaintYellow, kLime, kPaintBlue, kPaintPurple};

/// Детерминированный «шум» для клетки — чтобы кривизна не дрожала каждый кадр.
int hash(int x, int y) { return ((x * 92837111) ^ (y * 689287499)) & 0x7fffffff; }

int px(float v) { return static_cast<int>(std::floor(v)); }

}  // namespace

CrudeRenderer::CrudeRenderer(const GameContext& ctx) : ctx_(ctx), screen_(kW, kH, true) {}

void CrudeRenderer::beginLevel(const World& world, const platformer::Level&) {
    const Vec2 c = world.player().body.center();
    camera_ = {c.x * kTile - kW * 0.5f, c.y * kTile - kH * 0.6f};
    debris_.clear();
    popups_.clear();
    trail_.clear();
}

Vector2 CrudeRenderer::toScreen(Vec2 p) const {
    return {std::floor(p.x * kTile - camera_.x), std::floor(p.y * kTile - camera_.y)};
}

void CrudeRenderer::burst(Vec2 at, int count, Color color) {
    std::uniform_real_distribution<float> angle(0.f, 6.283f);
    std::uniform_real_distribution<float> speed(30.f, 90.f);
    for (int i = 0; i < count; ++i) {
        const float a = angle(rng_);
        const float s = speed(rng_);
        debris_.push_back({{at.x * kTile, at.y * kTile}, {std::cos(a) * s, std::sin(a) * s - 40.f}, color, 0.6f});
    }
}

void CrudeRenderer::popup(Vec2 at, std::string text) { popups_.push_back({{at.x * kTile, at.y * kTile}, std::move(text), 1.f}); }

void CrudeRenderer::onEvent(const WorldEvent& e, const World&) {
    switch (e.type) {
        case EventType::Pickup:
            burst(e.pos, 5, kPaintYellow);
            popup(e.pos, "+1");
            break;
        case EventType::Stomp:
        case EventType::DashKill:
            burst(e.pos, 10, kPaintRed);
            popup(e.pos, "БАЦ!");
            shake_ = 0.15f;
            break;
        case EventType::Hurt:
            popup(e.pos, "АЙ!");
            shake_ = 0.25f;
            glitchActive_ = 0.15f;
            break;
        case EventType::Death:
            burst(e.pos, 20, WHITE);
            shake_ = 0.4f;
            glitchActive_ = 0.4f;
            break;
        case EventType::Checkpoint: popup(e.pos, "СОХРАНЕНО (наверное)"); break;
        case EventType::BuddyMet:
            burst(e.pos, 16, e.value == 1 ? kLime : kPaintOrange);
            glitchActive_ = 0.5f;
            break;
        case EventType::Spring:
            popup(e.pos, "БОИНГ!");
            burst(e.pos, 6, WHITE);
            break;
        case EventType::Shard:
            burst(e.pos, 18, kPaintPurple);
            glitchActive_ = 0.3f;
            break;
        default: break;
    }
}

void CrudeRenderer::update(float dt, const World& world) {
    time_ += dt;
    shake_ = std::max(0.f, shake_ - dt);
    glitchActive_ = std::max(0.f, glitchActive_ - dt);

    // Глитч случается сам по себе, когда ему вздумается. Драйвер-то кривой.
    glitchTimer_ -= dt;
    if (glitchTimer_ <= 0.f) {
        std::uniform_real_distribution<float> next(3.f, 8.f);
        glitchTimer_ = next(rng_);
        glitchActive_ = 0.12f;
    }

    // Камера с «мёртвой зоной»: дёргается, только когда герой подходит к краю. Как в старых играх.
    const Vec2 c = world.player().body.center();
    const float sx = c.x * kTile - camera_.x;
    if (sx > kW * 0.62f) camera_.x += sx - kW * 0.62f;
    if (sx < kW * 0.38f) camera_.x += sx - kW * 0.38f;
    camera_.y = damp(camera_.y, c.y * kTile - kH * 0.6f, 3.f, dt);
    const float maxX = static_cast<float>(world.map().width()) * kTile - kW;
    const float maxY = static_cast<float>(world.map().height()) * kTile - kH;
    camera_.x = std::clamp(camera_.x, 0.f, std::max(0.f, maxX));
    // Уровень ниже экрана — прижимаем землю к нижнему краю, лишнее небо сверху.
    camera_.y = maxY < 0.f ? maxY : std::clamp(camera_.y, 0.f, maxY);
    camera_.x = std::floor(camera_.x);
    camera_.y = std::floor(camera_.y);

    for (auto& d : debris_) {
        d.vel.y += 200.f * dt;
        d.pos.x += d.vel.x * dt;
        d.pos.y += d.vel.y * dt;
        d.life -= dt;
    }
    std::erase_if(debris_, [](const Debris& d) { return d.life <= 0.f; });
    for (auto& p : popups_) {
        p.pos.y -= 15.f * dt;
        p.life -= dt;
    }
    std::erase_if(popups_, [](const Popup& p) { return p.life <= 0.f; });

    // Запоминаем путь героя: друзья идут за ним «паровозиком».
    if (trail_.empty() || distance(trail_.front(), c) > 0.08f) trail_.push_front(c);
    while (trail_.size() > 80) trail_.pop_back();
}

// ---------------------------------------------------------------- отрисовка

void CrudeRenderer::draw(const World& world, const StageView& view) {
    screen_.begin();
    drawBackground(view.levelIndex);
    drawTiles(world);
    drawEntities(world);
    drawPlayer(world);
    drawEffects();
    drawHud(world, view);

    if (view.levelTime < 2.5f && !view.dialogue) {
        drawWordArt(std::string(view.levelTitle), {kW * 0.5f, 60.f}, 20.f, time_);
    }
    if (world.player().dead) drawWordArt("ТЫ УМЕР", {kW * 0.5f, 70.f}, 30.f, time_);
    if (view.dialogue && !view.dialogue->finished()) drawDialogue(*view.dialogue);
    if (view.paused) drawPause(view.pauseSelection);
    if (view.fade > 0.f) DrawRectangle(0, 0, kW, kH, withAlpha(BLACK, view.fade));
    screen_.end();

    ClearBackground(kWin98Gray);
    present();
}

void CrudeRenderer::present() const {
    std::uniform_real_distribution<float> jitter(-2.f, 2.f);
    Vector2 offset{0.f, 0.f};
    if (shake_ > 0.f) offset = {std::round(jitter(rng_)), std::round(jitter(rng_))};
    if (glitchActive_ <= 0.f) {
        screen_.present(offset);
        return;
    }

    // Глитч: режем картинку на полосы и сдвигаем их как попало + подкрашиваем.
    const auto winW = static_cast<float>(GetScreenWidth());
    const auto winH = static_cast<float>(GetScreenHeight());
    float scale = std::min(winW / kW, winH / kH);
    if (scale >= 1.f) scale = std::floor(scale);
    const float ox = (winW - kW * scale) * 0.5f;
    const float oy = (winH - kH * scale) * 0.5f;
    std::uniform_int_distribution<int> stripH(4, 24);
    std::uniform_int_distribution<int> shift(-12, 12);
    std::uniform_int_distribution<int> tint(0, 3);
    const Color tints[] = {WHITE, {255, 120, 255, 255}, {120, 255, 255, 255}, {255, 255, 120, 255}};
    int y = 0;
    while (y < kH) {
        const int h = std::min(stripH(rng_), kH - y);
        const Rectangle src{0.f, static_cast<float>(kH - y - h), static_cast<float>(kW), -static_cast<float>(h)};
        const Rectangle dst{ox + static_cast<float>(shift(rng_)) * scale, oy + static_cast<float>(y) * scale,
                            kW * scale, static_cast<float>(h) * scale};
        DrawTexturePro(screen_.texture(), src, dst, {0.f, 0.f}, 0.f, tints[tint(rng_)]);
        y += h;
    }
}

void CrudeRenderer::drawBackground(int levelIndex) const {
    // У каждого уровня своё небо — насколько хватило палитры Paint.
    constexpr Color skies[] = {kPaintBlue, {112, 146, 190, 255}, {200, 191, 231, 255}, {255, 174, 201, 255}};
    ClearBackground(skies[static_cast<std::size_t>(levelIndex) % std::size(skies)]);

    // Солнце с лицом, нарисованное мышкой.
    const Vector2 sun{270.f, 30.f};
    DrawCircleV(sun, 16.f, kPaintYellow);
    for (int i = 0; i < 8; ++i) {
        const float a = static_cast<float>(i) * 0.785f + time_ * 0.3f;
        DrawLineV({sun.x + std::cos(a) * 19.f, sun.y + std::sin(a) * 19.f},
                  {sun.x + std::cos(a) * 26.f, sun.y + std::sin(a) * 27.f}, kPaintYellow);
    }
    DrawPixel(264, 26, BLACK);
    DrawPixel(275, 27, BLACK);
    DrawLine(263, 35, 277, 33, BLACK);  // улыбка кривая, как и всё здесь

    // Облака едут НЕ в ту сторону: параллакс с перепутанным знаком.
    for (int i = 0; i < 6; ++i) {
        float x = std::fmod(static_cast<float>(i) * 97.f + camera_.x * 0.3f + time_ * 6.f, kW + 80.f) - 40.f;
        const float y = 20.f + static_cast<float>((i * 37) % 50);
        DrawEllipse(px(x), px(y), 18.f, 7.f, WHITE);
        DrawEllipse(px(x + 12.f), px(y - 4.f), 12.f, 7.f, WHITE);
    }

    // Холмы и деревья «из Paint».
    for (int i = -1; i < 8; ++i) {
        const float x = static_cast<float>(i) * 70.f - std::fmod(camera_.x * 0.5f, 70.f);
        DrawCircle(px(x), kH + 30, 60.f, kPaintGreen);
    }
    for (int i = -1; i < 6; ++i) {
        const float x = static_cast<float>(i) * 90.f - std::fmod(camera_.x * 0.7f, 90.f) + 30.f;
        DrawRectangle(px(x), kH - 60, 6, 30, kBrown);
        DrawCircle(px(x + 3.f), kH - 66, 14.f, kLime);
    }
}

void CrudeRenderer::drawTiles(const World& world) const {
    const TileMap& map = world.map();
    const int x0 = std::max(0, px(camera_.x / kTile));
    const int x1 = std::min(map.width() - 1, px((camera_.x + kW) / kTile));
    const int y0 = std::max(0, px(camera_.y / kTile));
    const int y1 = std::min(map.height() - 1, px((camera_.y + kH) / kTile));
    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const Vector2 s = toScreen({static_cast<float>(x), static_cast<float>(y)});
            const int sx = static_cast<int>(s.x);
            const int sy = static_cast<int>(s.y);
            switch (map.at(x, y)) {
                case Tile::Solid: {
                    DrawRectangle(sx, sy, 10, 10, kBrown);
                    // Обводка, промахнувшаяся мимо блока на пиксель.
                    const int h = hash(x, y);
                    DrawRectangleLines(sx + (h % 3) - 1, sy + ((h / 3) % 3) - 1, 10, 10, BLACK);
                    if (!map.solid(x, y - 1)) {
                        DrawRectangle(sx, sy, 10, 3, kPaintGreen);
                        DrawPixel(sx + h % 10, sy + 3, kPaintGreen);  // травинка-неудачница
                    }
                    break;
                }
                case Tile::Spike:
                    for (int k = 0; k < 2; ++k) {
                        DrawTriangle({s.x + 5.f * k, s.y + 10.f}, {s.x + 5.f * k + 5.f, s.y + 10.f},
                                     {s.x + 5.f * k + 2.5f, s.y + 3.f}, kPaintRed);
                    }
                    break;
                case Tile::Empty: break;
            }
        }
    }
}

void CrudeRenderer::drawEntities(const World& world) const {
    // Финиш: флаг-шахматка и надпись.
    if (world.hasFinish()) {
        const Rect f = world.finish();
        const Vector2 s = toScreen({f.x, f.y});
        DrawRectangle(px(s.x + 4.f), px(s.y - 6.f), 2, 26, BLACK);
        for (int i = 0; i < 4; ++i) {
            for (int j = 0; j < 3; ++j) {
                DrawRectangle(px(s.x + 6.f) + i * 3, px(s.y - 6.f) + j * 3, 3, 3, (i + j) % 2 ? BLACK : WHITE);
            }
        }
        DrawTextEx(ctx_.fonts->crude, "ВЫХОД", {s.x - 6.f, s.y - 18.f}, 10.f, 0.f, kPaintRed);
    }

    for (const Checkpoint& c : world.checkpoints()) {
        const Vector2 s = toScreen({c.area.x, c.area.y});
        DrawRectangle(px(s.x + 4.f), px(s.y + 4.f), 2, 16, DARKGRAY);
        DrawTriangle({s.x + 6.f, s.y + 4.f}, {s.x + 6.f, s.y + 10.f}, {s.x + 13.f, s.y + 7.f},
                     c.active ? kLime : WHITE);  // синий флаг на синем небе не видно — пусть будет белый
    }

    for (const Pickup& p : world.pickups()) {
        if (p.taken) continue;
        const float bob = std::sin(time_ * 4.f + p.pos.x) * 1.5f;
        const Vector2 s = toScreen(p.pos);
        if (static_cast<int>(time_ * 6.f + p.pos.x) % 4 != 0) {  // мигает как гирлянда
            DrawRectangle(px(s.x - 2.f), px(s.y - 2.f + bob), 4, 4, kPaintYellow);
            DrawRectangleLines(px(s.x - 2.f), px(s.y - 2.f + bob), 4, 4, kPaintOrange);
        }
    }

    for (const Enemy& e : world.enemies()) {
        if (!e.alive) continue;
        const Rect r = e.body.rect();
        const Vector2 s = toScreen({r.x, r.y});
        const int w = px(r.w * kTile);
        const int h = px(r.h * kTile);
        const int leg = static_cast<int>(time_ * 10.f) % 2;
        DrawLine(px(s.x + 1.f), px(s.y) + h, px(s.x) + leg, px(s.y) + h + 2, BLACK);
        DrawLine(px(s.x) + w - 1, px(s.y) + h, px(s.x) + w - leg, px(s.y) + h + 2, BLACK);
        DrawRectangle(px(s.x), px(s.y), w, h, kPaintRed);
        const int eye = e.dir > 0 ? w - 4 : 1;
        DrawRectangle(px(s.x) + eye, px(s.y) + 1, 3, 3, WHITE);
        DrawPixel(px(s.x) + eye + (e.dir > 0 ? 2 : 0), px(s.y) + 2, BLACK);
    }

    for (const Spring& spring : world.springs()) {
        // Пружина: коробочка с зигзагом, которая сплющивается при срабатывании.
        const Vector2 s = toScreen({spring.area.x - 0.1f, spring.area.y - 0.5f});
        const int squash = static_cast<int>(spring.squash * 3.f);
        DrawRectangle(px(s.x) + 1, px(s.y) + 5 + squash, 8, 2, kPaintRed);
        DrawLine(px(s.x) + 2, px(s.y) + 10, px(s.x) + 5, px(s.y) + 7 + squash, DARKGRAY);
        DrawLine(px(s.x) + 5, px(s.y) + 7 + squash, px(s.x) + 8, px(s.y) + 10, DARKGRAY);
    }

    for (const Shard& shard : world.shards()) {
        if (shard.taken) continue;
        // Осколок памяти: фиолетовый ромб со знаком вопроса.
        const float bob = std::sin(time_ * 3.f + shard.pos.x) * 2.f;
        const Vector2 s = toScreen(shard.pos);
        DrawPoly({s.x, s.y + bob}, 4, 6.f, 0.f, kPaintPurple);
        DrawPolyLines({s.x, s.y + bob}, 4, 6.f, 0.f, BLACK);
        DrawText("?", px(s.x) - 2, px(s.y + bob) - 4, 10, WHITE);
    }

    for (const Buddy& b : world.buddies()) {
        if (b.met) continue;
        const Vector2 s = toScreen({b.pos.x - 0.35f, b.pos.y - 0.7f});
        const char* name = b.id == 1 ? "АЛИНА" : "ЕГОР";
        DrawRectangle(px(s.x), px(s.y), 7, 7, b.id == 1 ? kLime : kPaintOrange);
        DrawRectangleLines(px(s.x), px(s.y), 7, 7, BLACK);
        DrawPixel(px(s.x) + 2, px(s.y) + 2, BLACK);
        DrawPixel(px(s.x) + 4, px(s.y) + 2, BLACK);
        const float bob = std::sin(time_ * 6.f) * 2.f;
        DrawTextEx(ctx_.fonts->crude, name, {s.x - 8.f, s.y - 13.f + bob}, 10.f, 0.f, BLACK);
    }
}

void CrudeRenderer::drawPlayer(const World& world) const {
    const Player& p = world.player();

    // Друзья идут следом по пути героя: сначала Алина, за ней Егор.
    int followerIndex = 0;
    const auto follower = [&](Color color) {
        const std::size_t delay = static_cast<std::size_t>(14 + followerIndex * 14);
        ++followerIndex;
        if (trail_.size() <= delay) return;
        const Vec2 pos = trail_[delay];
        const Vector2 s = toScreen({pos.x - 0.3f, pos.y - 0.3f});
        DrawRectangle(px(s.x), px(s.y), 6, 6, color);
        DrawRectangleLines(px(s.x), px(s.y), 6, 6, BLACK);
        DrawPixel(px(s.x) + 2, px(s.y) + 2, BLACK);
        DrawPixel(px(s.x) + 4, px(s.y) + 2, BLACK);
    };
    if (world.abilities().doubleJump) follower(kLime);
    if (world.abilities().dash) follower(kPaintOrange);

    if (p.dead) return;
    if (p.invuln > 0.f && static_cast<int>(time_ * 20.f) % 2 == 0) return;

    const Rect r = p.body.rect();
    const Vector2 s = toScreen({r.x, r.y});
    const int x = px(s.x);
    const int y = px(s.y);
    const int size = px(r.w * kTile) + 1;
    DrawRectangle(x, y, size, size, WHITE);
    DrawRectangleLines(x, y, size, size, BLACK);
    const int look = p.facing > 0 ? 4 : 1;
    DrawPixel(x + look, y + 2, BLACK);
    DrawPixel(x + look + 2, y + 2, BLACK);
    DrawLine(x + 2, y + 6, x + 6, y + 5, BLACK);  // рот набекрень
    if (p.dashing()) DrawRectangle(x - p.facing * 8, y + 2, 6, 3, kPaintOrange);
}

void CrudeRenderer::drawEffects() const {
    for (const Debris& d : debris_) {
        DrawRectangle(px(d.pos.x - camera_.x), px(d.pos.y - camera_.y), 2, 2, d.color);
    }
    for (const Popup& p : popups_) {
        const Vector2 at{std::floor(p.pos.x - camera_.x), std::floor(p.pos.y - camera_.y - 8.f)};
        const std::string& t = p.text;
        DrawTextEx(ctx_.fonts->crude, t.c_str(), {at.x + 1.f, at.y + 1.f}, 10.f, 0.f, BLACK);
        DrawTextEx(ctx_.fonts->crude, t.c_str(), at, 10.f, 0.f, kPaintYellow);
    }
}

void CrudeRenderer::drawHud(const World& world, const StageView& view) const {
    const Font& f = ctx_.fonts->crude;
    DrawTextEx(f, "Unregistered HyperCam 2", {2.f, 1.f}, 10.f, 0.f, WHITE);

    DrawTextEx(f, "HP", {3.f, 13.f}, 10.f, 0.f, BLACK);
    for (int i = 0; i < world.player().maxHp; ++i) {
        const Color c = i < world.player().hp ? kPaintRed : DARKGRAY;
        DrawRectangle(18 + i * 8, 14, 6, 6, c);
        DrawRectangleLines(18 + i * 8, 14, 6, 6, BLACK);
    }
    const std::string pixels =
        "ПИКСЕЛИ " + std::to_string(world.pickupsCollected()) + "/" + std::to_string(world.pickups().size());
    DrawTextEx(f, pixels.c_str(), {3.f, 23.f}, 10.f, 0.f, BLACK);

    if (!world.shards().empty()) {
        const std::string shards =
            "ПАМЯТЬ " + std::to_string(world.shardsCollected()) + "/" + std::to_string(world.shards().size());
        DrawTextEx(f, shards.c_str(), {3.f, 33.f}, 10.f, 0.f, kPaintPurple);
    }

    const std::string lvl = "УРОВЕНЬ " + std::to_string(view.levelIndex + 1) + "/" + std::to_string(view.levelCount);
    DrawTextEx(f, lvl.c_str(), {kW - textWidth(f, lvl, 10.f, 0.f) - 3.f, 1.f}, 10.f, 0.f, WHITE);

    std::string skills;
    if (world.abilities().doubleJump) skills += "2x ПРЫЖОК ";
    if (world.abilities().dash) skills += "РЫВОК[SHIFT]";
    if (!skills.empty()) DrawTextEx(f, skills.c_str(), {3.f, kH - 11.f}, 10.f, 0.f, BLACK);
}

void CrudeRenderer::drawDialogue(const Dialogue& dialogue) const {
    const Font& f = ctx_.fonts->crude;
    // Окно диалога сверху: внизу земля и герой, их закрывать нельзя.
    const Rectangle box{6.f, 34.f, kW - 12.f, 62.f};
    DrawRectangleRec(box, BLACK);
    DrawRectangleLinesEx(box, 1.f, WHITE);

    float y = box.y + 4.f;
    const DialogueLine& line = dialogue.current();
    if (!line.speaker.empty()) {
        DrawTextEx(f, line.speaker.c_str(), {box.x + 5.f, y}, 10.f, 0.f, kPaintYellow);
        y += 11.f;
    }
    for (const auto& row : wrapText(f, dialogue.visibleText(), 10.f, 0.f, box.width - 10.f)) {
        DrawTextEx(f, row.c_str(), {box.x + 5.f, y}, 10.f, 0.f, WHITE);
        y += 10.f;
    }
    if (dialogue.lineFullyShown() && static_cast<int>(time_ * 3.f) % 2 == 0) {
        DrawTextEx(f, "[ENTER]", {box.x + box.width - 44.f, box.y + box.height - 11.f}, 10.f, 0.f, kPaintYellow);
    }
}

void CrudeRenderer::drawPause(int selection) const {
    const Font& f = ctx_.fonts->crude;
    DrawRectangle(0, 0, kW, kH, withAlpha(BLACK, 0.5f));
    const Rectangle box{kW * 0.5f - 70.f, 55.f, 140.f, 60.f};
    DrawRectangleRec(box, kWin98Gray);
    DrawRectangleLinesEx(box, 1.f, WHITE);
    DrawRectangle(px(box.x) + 1, px(box.y) + 1, px(box.width) - 2, 11, {0, 0, 128, 255});  // «заголовок окна»
    DrawTextEx(f, "Пауза.exe", {box.x + 4.f, box.y + 1.f}, 10.f, 0.f, WHITE);
    DrawTextEx(f, selection == 0 ? "> ПРОДОЛЖИТЬ" : "  ПРОДОЛЖИТЬ", {box.x + 10.f, box.y + 20.f}, 10.f, 0.f, BLACK);
    DrawTextEx(f, selection == 1 ? "> ВЫЙТИ (сохранено)" : "  ВЫЙТИ (сохранено)", {box.x + 10.f, box.y + 34.f}, 10.f,
               0.f, BLACK);
}

void CrudeRenderer::drawWordArt(std::string_view text, Vector2 center, float size, float time) const {
    // Каждая буква — своего цвета и прыгает. Шрифт 10px, растянутый в size — специально мыльный.
    const Font& f = ctx_.fonts->crude;
    const auto chars = utf8::decode(text);
    const float step = size * 0.62f;
    float x = center.x - step * static_cast<float>(chars.size()) * 0.5f;
    for (std::size_t i = 0; i < chars.size(); ++i) {
        const std::string ch = utf8::encode(chars[i]);
        const float wave = std::sin(time * 6.f + static_cast<float>(i) * 0.7f) * 3.f;
        const Color color = kWordArt[(i + static_cast<std::size_t>(time * 4.f)) % 6];
        DrawTextEx(f, ch.c_str(), {x + 2.f, center.y + wave + 2.f}, size, 0.f, BLACK);
        DrawTextEx(f, ch.c_str(), {x, center.y + wave}, size, 0.f, color);
        x += step;
    }
}

}  // namespace pix::gfx
