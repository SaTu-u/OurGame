#include "doctest.h"

#include <filesystem>

#include "core/Story.hpp"
#include "core/TextFile.hpp"
#include "platformer/TileMap.hpp"

using namespace pix;

TEST_CASE("story: реплика персонажа и слова рассказчика") {
    CHECK(parseDialogueLine("Алина: привет") == DialogueLine{"Алина", "привет"});
    CHECK(parseDialogueLine("> Цель: собрать байты") == DialogueLine{"", "Цель: собрать байты"});
    CHECK(parseDialogueLine("просто текст") == DialogueLine{"", "просто текст"});
}

TEST_CASE("story: секции и комментарии") {
    const Story story = Story::parse(
        "# комментарий\n"
        "[intro]\r\n"
        "> раз\n"
        "\n"
        "Егор: два\n"
        "[empty]\n");
    CHECK(story.has("intro"));
    CHECK(story.has("empty"));
    const auto intro = story.section("intro");
    REQUIRE(intro.size() == 2);
    CHECK(intro[0].text == "раз");
    CHECK(intro[1].speaker == "Егор");
}

TEST_CASE("story: неизвестная секция даёт видимую заглушку, а не падение") {
    const auto lines = Story{}.section("нет_такой");
    REQUIRE(lines.size() == 1);
    CHECK(lines[0].text.find("нет_такой") != std::string::npos);
}

TEST_CASE("story: в story.txt есть все секции, которые вызывает код") {
    const Story story = Story::loadFile(findAssetsDir("") / "story.txt");
    for (const char* name : {"terminal_intro", "terminal_outro", "meet_alina", "meet_egor", "crude_outro",
                             "pretty_outro", "finale", "epilogue", "credits_thanks"}) {
        CAPTURE(name);
        CHECK(story.has(name));
    }
}

TEST_CASE("story: у каждого разговора и осколка на уровнях есть текст") {
    const Story story = Story::loadFile(findAssetsDir("") / "story.txt");
    int talks = 0;
    int shards = 0;
    for (const auto& entry : std::filesystem::directory_iterator(findAssetsDir("") / "levels")) {
        const auto name = entry.path().filename().string();
        if (name.rfind("terminal", 0) == 0 || entry.path().extension() != ".txt") continue;
        const auto level = platformer::parseLevel(splitLines(*readTextFile(entry.path())));
        CAPTURE(name);
        CHECK(story.has(level.intro));
        for (const auto& section : level.talks) {
            CAPTURE(section);
            CHECK(story.has(section));
            ++talks;
        }
        for (const auto& section : level.shards) {
            CAPTURE(section);
            CHECK(story.has(section));
            ++shards;
        }
    }
    CHECK(talks >= 12);
    CHECK(shards == 7);  // семь осколков — семь записей о гибели мира
}

TEST_CASE("story: главные герои — Тимур, Алина и Егор") {
    const Story story = Story::loadFile(findAssetsDir("") / "story.txt");
    const auto speaks = [&](const char* section, const char* who) {
        for (const auto& line : story.section(section)) {
            if (line.speaker == who) return true;
        }
        return false;
    };
    CHECK(speaks("terminal_intro", "Тимур"));
    CHECK(speaks("meet_alina", "Алина"));
    CHECK(speaks("meet_egor", "Егор"));
    // В финальной сцене в реальном мире говорят все трое.
    for (const char* who : {"Тимур", "Алина", "Егор"}) CHECK(speaks("epilogue", who));
}

#include "core/Credits.hpp"

TEST_CASE("credits: секции, роли и имена") {
    const auto credits = parseCredits("# коммент\n[В ролях]\nПиксель | Тимур\nпросто строка\n[Команда]\nКод|Вася\n");
    REQUIRE(credits.size() == 2);
    CHECK(credits[0].title == "В ролях");
    REQUIRE(credits[0].entries.size() == 2);
    CHECK(credits[0].entries[0].role == "Пиксель");
    CHECK(credits[0].entries[0].name == "Тимур");
    CHECK(credits[0].entries[1].role.empty());
    CHECK(credits[1].entries[0].name == "Вася");
}
