#include "doctest.h"

#include <algorithm>

#include "platformer/World.hpp"

using namespace pix::platformer;

namespace {

void run(World& w, float seconds, Input in = {}) {
    const int steps = static_cast<int>(seconds / kFixedStep);
    for (int i = 0; i < steps; ++i) {
        w.update(kFixedStep, in);
        in.jumpPressed = false;
        in.dashPressed = false;
    }
}

bool happened(const std::vector<WorldEvent>& events, EventType type) {
    for (const auto& e : events) {
        if (e.type == type) return true;
    }
    return false;
}

const TileMap kFlat = TileMap::parse({
    "....................",
    "....................",
    "....................",
    "....................",
    "P...................",
    "####################",
});

}  // namespace

TEST_CASE("world: игрок стоит, бежит и прыгает") {
    World w(kFlat, PhysicsConfig::crude(), {});
    run(w, 0.2f);
    CHECK(w.player().body.onGround);

    Input right;
    right.right = true;
    const float x0 = w.player().body.pos.x;
    run(w, 0.5f, right);
    CHECK(w.player().body.pos.x > x0 + 2.f);

    Input jump;
    jump.jumpPressed = jump.jumpHeld = true;
    w.update(kFixedStep, jump);
    CHECK(happened(w.drainEvents(), EventType::Jump));
    CHECK(w.player().body.vel.y < 0.f);
    run(w, 0.15f, {.jumpHeld = true});
    CHECK(w.player().body.pos.y < 3.f);
}

TEST_CASE("world: без способности второго прыжка нет, с ней — есть") {
    for (bool doubleJump : {false, true}) {
        World w(kFlat, PhysicsConfig::crude(), {.doubleJump = doubleJump});
        run(w, 0.2f);
        w.update(kFixedStep, {.jumpHeld = true, .jumpPressed = true});
        run(w, 0.2f, {.jumpHeld = true});
        w.drainEvents();
        w.update(kFixedStep, {.jumpHeld = true, .jumpPressed = true});
        CHECK(happened(w.drainEvents(), EventType::AirJump) == doubleJump);
    }
}

TEST_CASE("world: «койотское время» в красивом 2D") {
    const TileMap ledge = TileMap::parse({
        "......",
        "......",
        "P.....",
        "##....",
    });
    World w(ledge, PhysicsConfig::polished(), {});
    run(w, 0.1f);
    // Бежим вправо, пока не сойдём с края.
    Input right;
    right.right = true;
    while (w.player().body.onGround) w.update(kFixedStep, right);
    run(w, 0.05f, right);  // уже в воздухе
    w.drainEvents();
    w.update(kFixedStep, {.right = true, .jumpHeld = true, .jumpPressed = true});
    CHECK(happened(w.drainEvents(), EventType::Jump));
}

TEST_CASE("world: прыжок на жука сверху убивает его") {
    const TileMap map = TileMap::parse({
        "P.......",
        "........",
        "........",
        "x.......",
        "########",
    });
    World w(map, PhysicsConfig::crude(), {});
    run(w, 1.f);
    const auto events = w.drainEvents();
    CHECK(happened(events, EventType::Stomp));
    CHECK_FALSE(w.enemies()[0].alive);
    CHECK(w.player().hp == w.player().maxHp);
}

TEST_CASE("world: жук сбоку ранит") {
    const TileMap map = TileMap::parse({
        "........",
        "P..x....",
        "########",
    });
    World w(map, PhysicsConfig::crude(), {});
    run(w, 2.f);
    CHECK(w.player().hp < 3);
    CHECK(happened(w.drainEvents(), EventType::Hurt));
}

TEST_CASE("world: шипы ранят, падение в пропасть — смерть, потом респавн") {
    const TileMap map = TileMap::parse({
        "P.........",
        "##^^^.....",
        "##########",
    });
    World w(map, PhysicsConfig::crude(), {});
    run(w, 0.1f);
    run(w, 0.5f, {.right = true});
    CHECK(w.player().hp < 3);

    const TileMap pit = TileMap::parse({"P...", "#...", "...."});
    World fall(pit, PhysicsConfig::crude(), {});
    run(fall, 0.1f);
    run(fall, 3.f, {.right = true});
    CHECK(fall.deaths() >= 1);
    run(fall, 1.f);
    CHECK(happened(fall.drainEvents(), EventType::Respawn));
}

TEST_CASE("world: пиксели, друзья, чекпоинты и финиш") {
    const TileMap map = TileMap::parse({
        "..........",
        "..........",
        "P.*.1.C..F",
        "##########",
    });
    World w(map, PhysicsConfig::crude(), {});
    CHECK(w.pickups().size() == 1);
    CHECK_FALSE(w.abilities().doubleJump);

    run(w, 3.f, {.right = true});
    const auto events = w.drainEvents();
    CHECK(w.pickupsCollected() == 1);
    CHECK(happened(events, EventType::BuddyMet));
    CHECK(w.abilities().doubleJump);  // Алина научила
    CHECK(happened(events, EventType::Checkpoint));
    CHECK(w.completed());
    CHECK(happened(events, EventType::LevelComplete));
}

TEST_CASE("world: друга, которого уже встречали, второй раз не встречаем") {
    const TileMap map = TileMap::parse({"P.1.2...", "########"});
    World w(map, PhysicsConfig::crude(), {.doubleJump = true});
    run(w, 2.f, {.right = true});
    int met = 0;
    for (const auto& e : w.drainEvents()) {
        if (e.type == EventType::BuddyMet) {
            ++met;
            CHECK(e.value == 2);  // только Егор
        }
    }
    CHECK(met == 1);
    CHECK(w.abilities().dash);
}

TEST_CASE("world: рывок открывается Егором и убивает жуков") {
    const TileMap map = TileMap::parse({
        "..........",
        "P....x....",
        "##########",
    });
    World w(map, PhysicsConfig::polished(), {.dash = true});
    run(w, 0.1f);
    w.update(kFixedStep, {.dashPressed = true});
    run(w, 0.3f);
    const auto events = w.drainEvents();
    CHECK(happened(events, EventType::Dash));
    CHECK(happened(events, EventType::DashKill));
    CHECK(w.player().hp == 3);
}

TEST_CASE("world: пружина подбрасывает выше обычного прыжка") {
    const TileMap map = TileMap::parse({
        "......",
        "......",
        "......",
        "......",
        "......",
        "......",
        "......",
        "P.S...",
        "######",
    });
    // Обычный прыжок — около 2.8 клетки.
    World jumper(map, PhysicsConfig::crude(), {});
    run(jumper, 0.1f);
    jumper.update(kFixedStep, {.jumpHeld = true, .jumpPressed = true});
    float jumpTop = jumper.player().body.pos.y;
    for (int i = 0; i < 120; ++i) {
        jumper.update(kFixedStep, {.jumpHeld = true});
        jumpTop = std::min(jumpTop, jumper.player().body.pos.y);
    }

    World w(map, PhysicsConfig::crude(), {});
    run(w, 0.1f);
    float springTop = w.player().body.pos.y;
    bool sprung = false;
    for (int i = 0; i < 240; ++i) {
        w.update(kFixedStep, {.right = i < 30});
        springTop = std::min(springTop, w.player().body.pos.y);
        sprung = sprung || happened(w.drainEvents(), EventType::Spring);
    }
    CHECK(sprung);
    CHECK(springTop < jumpTop - 2.f);  // минимум на две клетки выше
}

TEST_CASE("world: сюжетная точка срабатывает один раз, даже если перепрыгнуть её сверху") {
    const TileMap map = TileMap::parse({
        "........",
        "....!...",
        "........",
        "P.......",
        "########",
    });
    World w(map, PhysicsConfig::crude(), {});
    run(w, 0.1f);
    int fired = 0;
    for (int i = 0; i < 360; ++i) {
        // Бежим вправо и всё время прыгаем — точка всё равно должна сработать.
        w.update(kFixedStep, {.right = true, .jumpHeld = true, .jumpPressed = i % 60 == 0});
        for (const auto& e : w.drainEvents()) {
            if (e.type == EventType::StoryPoint) {
                ++fired;
                CHECK(e.value == 0);
            }
        }
    }
    CHECK(fired == 1);
}

TEST_CASE("world: осколок памяти — необязательная находка, мимо можно пройти") {
    const TileMap map = TileMap::parse({
        "...?....",
        "........",
        "........",
        "P......F",
        "########",
    });
    // Прошли по земле — осколок остался висеть.
    World walker(map, PhysicsConfig::crude(), {});
    run(walker, 2.f, {.right = true});
    CHECK(walker.completed());
    CHECK(walker.shardsCollected() == 0);

    // Подпрыгнули под ним — подобрали.
    World jumper(map, PhysicsConfig::crude(), {});
    run(jumper, 0.1f);
    run(jumper, 0.28f, {.right = true});
    jumper.update(kFixedStep, {.jumpHeld = true, .jumpPressed = true});
    run(jumper, 0.5f, {.jumpHeld = true});
    CHECK(jumper.shardsCollected() == 1);
    CHECK(happened(jumper.drainEvents(), EventType::Shard));
}

TEST_CASE("world: уровень можно завершить принудительно (режим показа)") {
    World w(kFlat, PhysicsConfig::crude(), {});
    CHECK_FALSE(w.completed());
    w.forceComplete();
    CHECK(w.completed());
}
