#include "doctest.h"

#include "core/Options.hpp"
#include "core/Progress.hpp"

using namespace pix;

TEST_CASE("progress: сохранение и загрузка дают то же самое") {
    Progress p;
    p.stage = StageId::Pretty;
    p.level = 1;
    p.doubleJump = true;
    p.bits = 24;
    p.pixels = 17;
    p.shards = 4;
    p.deaths = 5;
    p.playSeconds = 123.5;
    CHECK(Progress::deserialize(p.serialize()) == p);
}

TEST_CASE("progress: мусор в файле сохранения не ломает игру") {
    const Progress p = Progress::deserialize("stage=???\nlevel=abc\nhello\ndash=1\n=\n");
    CHECK(p.stage == StageId::Terminal);
    CHECK(p.level == 0);
    CHECK(p.dash);
}

TEST_CASE("progress: стадию можно задать номером или именем") {
    CHECK(parseStage("2") == StageId::Crude);
    CHECK(parseStage("pretty") == StageId::Pretty);
    CHECK_FALSE(parseStage("5").has_value());
    CHECK_FALSE(parseStage("3d").has_value());
}

TEST_CASE("options: аргументы командной строки") {
    const Options o = parseOptions({"--stage", "3", "--new"});
    CHECK(o.startStage == StageId::Pretty);
    CHECK(o.fresh);
    CHECK(o.errors.empty());

    const Options bad = parseOptions({"--stage", "--what"});
    CHECK_FALSE(bad.errors.empty());

    const Options demo = parseOptions({"--stage", "crude", "--level", "3", "--demo"});
    CHECK(demo.errors.empty());
    CHECK(demo.startStage == StageId::Crude);
    CHECK(demo.startLevel == 2);  // в командной строке уровни считаются с единицы
    CHECK(demo.demo);

    CHECK_FALSE(parseOptions({"--level", "2"}).errors.empty());  // без --stage нельзя
    CHECK_FALSE(parseOptions({"--stage", "2", "--level", "0"}).errors.empty());
}
