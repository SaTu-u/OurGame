#include "doctest.h"

#include "core/TextFile.hpp"
#include "terminal/TerminalWorld.hpp"

using namespace pix::terminal;

namespace {

// Маленькая карта без случайностей: ни бит, ни жуков, ни сборщика мусора.
Config quietConfig() {
    Config c;
    c.targetBits = 0;
    c.bugsBase = 0;
    c.bugsPerByte = 0;
    c.firstGcDelay = 1000.f;
    return c;
}

const std::vector<std::string> kRoom = {
    "##########",
    "#@.......#",
    "#........#",
    "#.....>..#",
    "##########",
};

void feedByte(TerminalWorld& w) {
    // Кладём бит в соседнюю клетку и наступаем на него — туда-обратно 8 раз.
    for (int i = 0; i < 8; ++i) {
        const Dir d = i % 2 == 0 ? Dir::Right : Dir::Left;
        w.placeBit(step(w.hero(), d), '1');
        w.move(d);
    }
}

}  // namespace

TEST_CASE("terminal: стены не пускают героя") {
    TerminalWorld w(kRoom, quietConfig());
    CHECK(w.hero() == Point{1, 1});
    w.move(Dir::Up);
    w.move(Dir::Left);
    CHECK(w.hero() == Point{1, 1});
    w.move(Dir::Right);
    CHECK(w.hero() == Point{2, 1});
}

TEST_CASE("terminal: без героя на карте — понятная ошибка") {
    CHECK_THROWS_AS(TerminalWorld({"####", "#..#", "####"}, quietConfig()), std::runtime_error);
}

TEST_CASE("terminal: 8 бит = байт, байты открывают навыки и портал") {
    TerminalWorld w(kRoom, quietConfig());
    CHECK_FALSE(w.eraseUnlocked());

    feedByte(w);
    CHECK(w.bytes() == 1);
    CHECK(w.totalBits() == 8);
    CHECK(w.currentByte().empty());
    CHECK(w.eraseUnlocked());
    CHECK_FALSE(w.dashUnlocked());

    feedByte(w);
    CHECK(w.dashUnlocked());
    CHECK_FALSE(w.portalOpen());

    feedByte(w);
    CHECK(w.portalOpen());

    bool opened = false;
    for (const auto& e : w.drainEvents()) opened = opened || e.type == EventType::PortalOpened;
    CHECK(opened);
}

TEST_CASE("terminal: жук идёт к герою кратчайшим путём и кусает") {
    TerminalWorld w(kRoom, quietConfig());
    w.placeBug({5, 1});
    CHECK(w.distanceToHero({5, 1}) == 4);

    for (int i = 0; i < 40 && w.brightness() == 3; ++i) w.update(0.1f);
    CHECK(w.brightness() == 2);
    CHECK(w.heroInvulnerable());
}

TEST_CASE("terminal: Backspace стирает жуков вокруг") {
    TerminalWorld w(kRoom, quietConfig());
    feedByte(w);
    w.placeBug({w.hero().x + 2, w.hero().y + 1});
    w.placeBug({8, 3});  // далеко — выживет
    REQUIRE(w.erase());
    CHECK(w.bugs().size() == 1);
    CHECK_FALSE(w.erase());  // перезарядка
}

TEST_CASE("terminal: рывок пролетает сквозь жуков и останавливается у стены") {
    TerminalWorld w(kRoom, quietConfig());
    feedByte(w);
    feedByte(w);
    w.placeBug({4, 1});
    while (w.hero().x != 1) w.move(Dir::Left);
    w.move(Dir::Right);  // смотрим вправо, стоим на x=2
    REQUIRE(w.dash());
    CHECK(w.hero() == Point{7, 1});
    CHECK(w.bugs().empty());
    CHECK(w.brightness() == 3);
}

TEST_CASE("terminal: сборщик мусора наказывает тех, кто стоит без дела") {
    Config c = quietConfig();
    c.firstGcDelay = 1.f;
    c.gcWarning = 0.1f;
    c.bugStepBase = c.bugStepMin = 1000.f;  // жук стоит на месте
    TerminalWorld w(kRoom, c);
    w.placeBug({8, 2});
    for (int i = 0; i < 100; ++i) w.update(0.05f);
    CHECK(w.brightness() < 3);  // стоял — получил
    CHECK(w.bugs().empty());    // жука тоже собрали
}

TEST_CASE("terminal: кто двигается — того сборщик не трогает") {
    Config c = quietConfig();
    c.firstGcDelay = 0.1f;
    c.gcWarning = 0.1f;
    TerminalWorld w(kRoom, c);
    for (int i = 0; i < 100; ++i) {
        w.move(i % 2 == 0 ? Dir::Down : Dir::Up);
        w.update(0.05f);
    }
    CHECK(w.brightness() == 3);
}

TEST_CASE("terminal: три укуса — и пиксель гаснет") {
    TerminalWorld w(kRoom, quietConfig());
    for (int i = 0; i < 3; ++i) {
        w.placeBug({w.hero().x + 1, w.hero().y});
        w.move(Dir::Right);  // врезаемся в жука
        w.clearBugs();
        w.update(2.f);  // ждём, пока пройдёт неуязвимость
    }
    CHECK(w.dead());
}

TEST_CASE("terminal: карта игры корректна — 60x18, есть герой и портал") {
    const auto text = pix::readTextFile(pix::findAssetsDir("") / "levels" / "terminal.txt");
    REQUIRE(text.has_value());
    const auto lines = pix::splitLines(*text);
    CHECK(lines.size() == 18);
    for (const auto& l : lines) CHECK(l.size() == 60);
    TerminalWorld w(lines, Config{}, 7);
    CHECK(w.bits().size() == static_cast<std::size_t>(Config{}.targetBits));
    // Портал достижим от старта.
    CHECK(w.distanceToHero(w.portal()) > 0);
}
