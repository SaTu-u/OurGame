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
}
