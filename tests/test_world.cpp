#include "doctest.h"

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

TEST_CASE("world: пиксели, кенты, чекпоинты и финиш") {
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
    CHECK(w.abilities().doubleJump);
    CHECK(happened(events, EventType::Checkpoint));
    CHECK(w.completed());
    CHECK(happened(events, EventType::LevelComplete));
}

TEST_CASE("world: рывок открывается Шейдером и убивает жуков") {
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

TEST_CASE("world: босс просыпается, падает, и его можно добить") {
    const TileMap arena = TileMap::parse({
        "....................",
        "..........B.........",
        "....................",
        "....................",
        "....................",
        "....................",
        "P...................",
        "####################",
    });
    World w(arena, PhysicsConfig::polished(), {});
    REQUIRE(w.boss().present);
    run(w, 0.1f);
    CHECK(happened(w.drainEvents(), EventType::BossAwake));

    // Имитируем удары: оглушаем босса и прыгаем ему на голову.
    int guard = 0;
    while (w.boss().state != BossState::Dead && guard++ < 10) {
        Boss& b = w.mutableBoss();
        b.state = BossState::Stunned;
        b.timer = 5.f;
        Player& p = w.mutablePlayer();
        p.invuln = 0.f;
        p.body.pos = {b.body.center().x - 0.375f, b.body.pos.y - 0.8f};
        p.body.vel = {0.f, 5.f};
        run(w, 0.1f);
    }
    CHECK(w.boss().state == BossState::Dead);
    CHECK(w.completed());
    CHECK(happened(w.drainEvents(), EventType::BossDefeated));
}
