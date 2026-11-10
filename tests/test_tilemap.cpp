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

TEST_CASE("tilemap: уровень без старта — ошибка") {
    CHECK_THROWS(parseLevel({"...", "###"}));
}

TEST_CASE("tilemap: все уровни игры загружаются") {
    const auto dir = pix::findAssetsDir("") / "levels";
    int count = 0;
    for (const auto& entry : std::filesystem::directory_iterator(dir)) {
        const auto name = entry.path().filename().string();
        if (name == "terminal.txt" || entry.path().extension() != ".txt") continue;
        CAPTURE(name);
        const auto text = pix::readTextFile(entry.path());
        REQUIRE(text.has_value());
        const Level level = parseLevel(pix::splitLines(*text));
        CHECK_FALSE(level.title.empty());
        const World world(level.map, PhysicsConfig::crude(), {});
        CHECK((world.hasFinish() || world.boss().present));
        ++count;
    }
    CHECK(count >= 4);
}
