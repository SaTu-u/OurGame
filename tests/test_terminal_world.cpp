#include "doctest.h"

#include "core/Story.hpp"
#include "core/TextFile.hpp"
#include "terminal/Sectors.hpp"
#include "terminal/TerminalWorld.hpp"

using namespace pix::terminal;

namespace {

// Маленькая карта без случайностей: ни бит, ни жуков, ни Обнуления, ходить можно без задержки.
Config quietConfig() {
    Config c;
    c.moveInterval = 0.f;
    c.targetBits = 0;
    c.bugsBase = 0;
    c.bugsPerByte = 0;
    c.firstWipeDelay = 1000.f;
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

TEST_CASE("terminal: Обнуление стирает тех, кто стоит на месте") {
    Config c = quietConfig();
    c.firstWipeDelay = 1.f;
    c.wipeWarning = 0.1f;
    c.bugStepBase = c.bugStepMin = 1000.f;  // жук стоит на месте
    TerminalWorld w(kRoom, c);
    w.placeBug({8, 2});
    for (int i = 0; i < 100; ++i) w.update(0.05f);
    CHECK(w.brightness() < 3);  // стоял — получил
    CHECK(w.bugs().empty());    // жука тоже стёрло
}

TEST_CASE("terminal: кто двигается — того Обнуление не трогает") {
    Config c = quietConfig();
    c.firstWipeDelay = 0.1f;
    c.wipeWarning = 0.1f;
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

TEST_CASE("terminal: зажатая клавиша не даёт «телепорт» — шаги ограничены по времени") {
    Config c = quietConfig();
    c.moveInterval = 0.1f;
    TerminalWorld w(kRoom, c);
    for (int i = 0; i < 5; ++i) w.move(Dir::Right);  // пять нажатий в один момент
    CHECK(w.hero() == Point{2, 1});                  // сделан только один шаг
    w.update(0.11f);
    w.move(Dir::Right);
    CHECK(w.hero() == Point{3, 1});
}

TEST_CASE("terminal: во тьме видно только то, что рядом") {
    Config c = quietConfig();
    c.visionRadius = 4;
    TerminalWorld w(kRoom, c);
    CHECK(w.visible({2, 1}));
    CHECK(w.visible({5, 1}));
    CHECK_FALSE(w.visible({8, 1}));
    CHECK_FALSE(w.visible({1, 4}));

    TerminalWorld bright(kRoom, quietConfig());
    CHECK(bright.visible({8, 3}));
}

TEST_CASE("terminal: все секторы игры корректны — карта 60x18, герой, достижимый портал") {
    const auto& all = sectors();
    REQUIRE(all.size() == 3);
    const pix::Story story = pix::Story::loadFile(pix::findAssetsDir("") / "story.txt");
    int totalBytes = 0;
    for (std::size_t i = 0; i < all.size(); ++i) {
        const Sector& sector = all[i];
        CAPTURE(sector.mapFile);
        const auto text = pix::readTextFile(pix::findAssetsDir("") / "levels" / sector.mapFile);
        REQUIRE(text.has_value());
        const auto lines = pix::splitLines(*text);
        CHECK(lines.size() == 18);
        for (const auto& l : lines) CHECK(l.size() == 60);

        TerminalWorld w(lines, sector.config, 7);
        CHECK(w.bits().size() == static_cast<std::size_t>(sector.config.targetBits));
        CHECK(w.distanceToHero(w.portal()) > 0);
        CHECK(story.has(sector.intro));
        // У каждого байта в каждом секторе есть реплика «голоса».
        for (int b = 1; b <= sector.config.bytesToWin; ++b) CHECK(story.has(voiceSection(static_cast<int>(i), b)));
        totalBytes += sector.config.bytesToWin;
    }
    CHECK(totalBytes == 8);  // «восемь байт» упоминаются в сюжете

    // Навыки открываются по ходу стадии: стирание — в первом секторе, рывок — во втором.
    CHECK(all[0].config.eraseUnlockBytes == 1);
    CHECK(all[0].config.dashUnlockBytes > all[0].config.bytesToWin);
    CHECK(all[1].config.dashUnlockBytes <= all[1].config.bytesToWin);
    CHECK(all[2].config.visionRadius > 0);
}

TEST_CASE("terminal: сектор можно завершить принудительно (режим показа)") {
    TerminalWorld w(kRoom, quietConfig());
    CHECK_FALSE(w.escaped());
    w.forceEscape();
    CHECK(w.escaped());
}
