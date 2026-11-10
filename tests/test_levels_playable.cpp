// «Бот-тестировщик»: перебирает действия игрока поиском в ширину и проверяет,
// что каждый уровень можно пройти. Если кто-то нарисует слишком широкую пропасть
// или стену, на которую не запрыгнуть, — тест упадёт ещё до того, как это заметит игрок.

#include "doctest.h"

#include <deque>
#include <optional>
#include <set>
#include <tuple>

#include "core/TextFile.hpp"
#include "platformer/World.hpp"

using namespace pix::platformer;

namespace {

constexpr int kStepsPerAction = 10;  // одно «решение» бота длится 1/12 секунды
constexpr int kMaxExpansions = 250000;

Level load(const char* file) {
    const auto text = pix::readTextFile(pix::findAssetsDir("") / "levels" / file);
    REQUIRE(text.has_value());
    return parseLevel(pix::splitLines(*text));
}

using Key = std::tuple<int, int, bool, int, bool, bool, bool>;

Key keyOf(const World& w) {
    const Player& p = w.player();
    return {static_cast<int>(p.body.pos.x * 3.f), static_cast<int>(p.body.pos.y * 3.f), p.body.onGround, p.airJumps,
            p.dashCooldown > 0.f, w.abilities().doubleJump, w.abilities().dash};
}

/// true — бот нашёл путь до финиша.
bool botCanFinish(const Level& level, PhysicsConfig physics, Abilities start) {
    std::deque<World> queue;
    std::set<Key> seen;
    queue.emplace_back(level.map, physics, start);
    for (int i = 0; i < 30; ++i) queue.back().update(kFixedStep, {});  // встать на землю
    seen.insert(keyOf(queue.back()));

    int expansions = 0;
    while (!queue.empty() && expansions++ < kMaxExpansions) {
        const World current = std::move(queue.front());
        queue.pop_front();
        for (int dir = -1; dir <= 1; ++dir) {
            for (int jump = 0; jump <= 1; ++jump) {
                for (int dash = 0; dash <= (current.abilities().dash ? 1 : 0); ++dash) {
                    World next = current;
                    for (int s = 0; s < kStepsPerAction; ++s) {
                        Input in;
                        in.left = dir < 0;
                        in.right = dir > 0;
                        in.jumpHeld = jump == 1;
                        in.jumpPressed = jump == 1 && s == 0;
                        in.dashPressed = dash == 1 && s == 0;
                        next.update(kFixedStep, in);
                    }
                    next.drainEvents();
                    if (next.completed()) return true;
                    // Бот играет аккуратно: смерть и потеря жизней — тупиковые ветки.
                    if (next.player().dead || next.player().hp < next.player().maxHp) continue;
                    if (seen.insert(keyOf(next)).second) queue.push_back(std::move(next));
                }
            }
        }
    }
    return false;
}

}  // namespace

TEST_CASE("уровни проходимы: стрёмное 2D") {
    CHECK(botCanFinish(load("crude_1.txt"), PhysicsConfig::crude(), {}));
    CHECK(botCanFinish(load("crude_2.txt"), PhysicsConfig::crude(), {}));
}

TEST_CASE("уровни проходимы: красивое 2D") {
    CHECK(botCanFinish(load("pretty_1.txt"), PhysicsConfig::polished(), {.doubleJump = true}));
}

TEST_CASE("без рывка широкую пропасть в красивом 2D не перепрыгнуть") {
    // Проверяем, что кент Шейдер действительно нужен: убираем его с карты.
    Level level = load("pretty_1.txt");
    auto text = pix::readTextFile(pix::findAssetsDir("") / "levels" / "pretty_1.txt");
    for (auto& c : *text) {
        if (c == '2') c = '.';
    }
    level = parseLevel(pix::splitLines(*text));
    CHECK_FALSE(botCanFinish(level, PhysicsConfig::polished(), {.doubleJump = true}));
}
