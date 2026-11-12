#include "doctest.h"

#include <filesystem>

#include "core/TextFile.hpp"
#include "platformer/TileMap.hpp"
#include "platformer/World.hpp"

using namespace pix::platformer;

TEST_CASE("tilemap: символы превращаются в тайлы и точки появления") {
    const TileMap map = TileMap::parse({
        "P..*",
        "#^##",
    });
    CHECK(map.width() == 4);
    CHECK(map.height() == 2);
    CHECK(map.at(0, 1) == Tile::Solid);
    CHECK(map.at(1, 1) == Tile::Spike);
    CHECK(map.at(1, 0) == Tile::Empty);
    REQUIRE(map.find('P').has_value());
    CHECK(map.find('P')->x == 0);
    CHECK(map.find('*')->x == 3);
}

TEST_CASE("tilemap: за краями карты — стены по бокам и пропасть снизу") {
    const TileMap map = TileMap::parse({"...", "###"});
    CHECK(map.at(-1, 0) == Tile::Solid);
    CHECK(map.at(3, 0) == Tile::Solid);
    CHECK(map.at(1, -5) == Tile::Empty);
    CHECK(map.at(1, 10) == Tile::Empty);
}

TEST_CASE("tilemap: служебные строки уровня") {
    const Level level = parseLevel({"@title Кривой лес", "@intro crude_intro", "; комментарий", "P..", "###", ""});
    CHECK(level.title == "Кривой лес");
    CHECK(level.intro == "crude_intro");
    CHECK(level.map.height() == 2);
}

TEST_CASE("tilemap: сюжетные точки и осколки привязаны к тексту слева направо") {
    const Level level = parseLevel({
        "@title Тест",
        "@talk первый",
        "@talk второй",
        "@shard память",
        "P.!.?.!.",
        "########",
    });
    REQUIRE(level.talks.size() == 2);
    CHECK(level.talks[0] == "первый");
    CHECK(level.talks[1] == "второй");
    REQUIRE(level.shards.size() == 1);
    const auto points = level.map.all('!');
    REQUIRE(points.size() == 2);
    CHECK(points[0].x == 2);
    CHECK(points[1].x == 6);
}

TEST_CASE("tilemap: «!» без текста или текст без «!» — ошибка в уровне") {
    CHECK_THROWS(parseLevel({"@talk лишний", "P...", "####"}));
    CHECK_THROWS(parseLevel({"P.!.", "####"}));
    CHECK_THROWS(parseLevel({"P.?.", "####"}));
}

TEST_CASE("tilemap: уровень без старта — ошибка") {
    CHECK_THROWS(parseLevel({"...", "###"}));
}

TEST_CASE("tilemap: все уровни игры загружаются, у каждого есть название и финиш") {
    const auto dir = pix::findAssetsDir("") / "levels";
    int count = 0;
    for (const auto& entry : std::filesystem::directory_iterator(dir)) {
        const auto name = entry.path().filename().string();
        if (name.rfind("terminal", 0) == 0 || entry.path().extension() != ".txt") continue;
        CAPTURE(name);
        const auto text = pix::readTextFile(entry.path());
        REQUIRE(text.has_value());
        const Level level = parseLevel(pix::splitLines(*text));
        CHECK_FALSE(level.title.empty());
        CHECK_FALSE(level.intro.empty());
        const World world(level.map, PhysicsConfig::crude(), {});
        CHECK(world.hasFinish());
        CHECK_FALSE(world.checkpoints().empty());
        ++count;
    }
    CHECK(count == 8);  // 4 уровня стрёмного 2D + 4 красивого
}
