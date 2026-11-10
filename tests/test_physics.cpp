#include "doctest.h"

#include "platformer/Physics.hpp"

using namespace pix::platformer;

namespace {

const TileMap kBox = TileMap::parse({
    "..........",
    "..........",
    "..........",
    "....#.....",
    "##########",
});

void simulate(Body& b, float seconds, float gravity = 75.f) {
    for (float t = 0.f; t < seconds; t += 1.f / 120.f) {
        b.vel.y += gravity / 120.f;
        moveAndCollide(b, kBox, 1.f / 120.f);
    }
}

}  // namespace

TEST_CASE("physics: тело падает и встаёт на пол") {
    Body b;
    b.size = {0.75f, 0.75f};
    b.pos = {1.f, 0.f};
    simulate(b, 1.f);
    CHECK(b.onGround);
    CHECK(b.pos.y == doctest::Approx(4.f - 0.75f));
    CHECK(b.vel.y == doctest::Approx(0.f));
}

TEST_CASE("physics: стена останавливает движение вбок") {
    Body b;
    b.size = {0.75f, 0.75f};
    b.pos = {2.f, 3.25f};
    b.vel.x = 10.f;
    bool hit = false;
    for (int i = 0; i < 60; ++i) {
        moveAndCollide(b, kBox, 1.f / 120.f);
        hit = hit || b.hitWall;
    }
    CHECK(hit);
    CHECK(b.pos.x == doctest::Approx(4.f - 0.75f));
}

TEST_CASE("physics: из карты не выйти через боковой край") {
    Body b;
    b.size = {0.75f, 0.75f};
    b.pos = {0.5f, 3.25f};
    b.vel.x = -10.f;
    for (int i = 0; i < 60; ++i) moveAndCollide(b, kBox, 1.f / 120.f);
    CHECK(b.pos.x == doctest::Approx(0.f));
}

TEST_CASE("physics: потолок обнуляет скорость вверх") {
    const TileMap ceiling = TileMap::parse({"####", "....", "....", "####"});
    Body b;
    b.size = {0.75f, 0.75f};
    b.pos = {1.f, 2.2f};
    b.vel.y = -20.f;
    bool hit = false;
    for (int i = 0; i < 20; ++i) {
        moveAndCollide(b, ceiling, 1.f / 120.f);
        hit = hit || b.hitCeiling;
    }
    CHECK(hit);
    CHECK(b.pos.y == doctest::Approx(1.f));
}

TEST_CASE("physics: хитбокс шипов — только нижняя половина клетки") {
    const TileMap spikes = TileMap::parse({"...", ".^.", "###"});
    CHECK(touchesSpikes({1.2f, 1.6f, 0.5f, 0.3f}, spikes));
    CHECK_FALSE(touchesSpikes({1.2f, 1.0f, 0.5f, 0.3f}, spikes));
    CHECK_FALSE(touchesSpikes({0.0f, 1.6f, 0.5f, 0.3f}, spikes));
}
