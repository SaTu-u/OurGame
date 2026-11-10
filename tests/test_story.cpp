#include "doctest.h"

#include "core/Story.hpp"
#include "core/TextFile.hpp"

using namespace pix;

TEST_CASE("story: реплика персонажа и слова рассказчика") {
    CHECK(parseDialogueLine("Глюк: привет") == DialogueLine{"Глюк", "привет"});
    CHECK(parseDialogueLine("> Цель: собрать байты") == DialogueLine{"", "Цель: собрать байты"});
    CHECK(parseDialogueLine("просто текст") == DialogueLine{"", "просто текст"});
}

TEST_CASE("story: секции и комментарии") {
    const Story story = Story::parse(
        "# комментарий\n"
        "[intro]\r\n"
        "> раз\n"
        "\n"
        "Шейдер: два\n"
        "[empty]\n");
    CHECK(story.has("intro"));
    CHECK(story.has("empty"));
    const auto intro = story.section("intro");
    REQUIRE(intro.size() == 2);
    CHECK(intro[0].text == "раз");
    CHECK(intro[1].speaker == "Шейдер");
}

TEST_CASE("story: неизвестная секция даёт видимую заглушку, а не падение") {
    const auto lines = Story{}.section("нет_такой");
    REQUIRE(lines.size() == 1);
    CHECK(lines[0].text.find("нет_такой") != std::string::npos);
}

TEST_CASE("story: настоящий story.txt содержит все секции, которые использует игра") {
    const Story story = Story::loadFile(findAssetsDir("") / "story.txt");
    for (const char* name : {"terminal_intro", "terminal_outro", "crude_intro", "crude_glitch", "crude_outro",
                             "pretty_intro", "pretty_shader", "boss_intro", "boss_defeat", "finale",
                             "credits_thanks"}) {
        CAPTURE(name);
        CHECK(story.has(name));
    }
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
