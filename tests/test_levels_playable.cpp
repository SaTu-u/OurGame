// «Бот-тестировщик»: перебирает действия игрока поиском в ширину и проверяет,
// что каждый уровень можно пройти. Если кто-то нарисует слишком широкую пропасть
// или стену, на которую не запрыгнуть, — тест упадёт ещё до того, как это заметит игрок.

#include "doctest.h"

#include <algorithm>
#include <deque>
#include <optional>
#include <set>
#include <string>
#include <tuple>

#include "core/TextFile.hpp"
#include "platformer/World.hpp"

using namespace pix::platformer;

namespace {

constexpr int kStepsPerAction = 10;  // одно «решение» бота длится 1/12 секунды
constexpr int kMaxExpansions = 600000;

std::string levelText(const char* file) {
    const auto text = pix::readTextFile(pix::findAssetsDir("") / "levels" / file);
    REQUIRE(text.has_value());
    return *text;
}

Level load(const char* file) { return parseLevel(pix::splitLines(levelText(file))); }

/// Уровень, с карты которого убрали все символы symbol (например, друга, дающего навык).
Level loadWithout(const char* file, char symbol) {
    std::string text = levelText(file);
    for (char& c : text) {
        if (c == symbol) c = '.';
    }
    return parseLevel(pix::splitLines(text));
}

using Key = std::tuple<int, int, bool, int, bool, bool, bool, int>;

Key keyOf(const World& w) {
    const Player& p = w.player();
    return {static_cast<int>(p.body.pos.x * 3.f), static_cast<int>(p.body.pos.y * 3.f), p.body.onGround, p.airJumps,
            p.dashCooldown > 0.f, w.abilities().doubleJump, w.abilities().dash, w.shardsCollected()};
}

struct Goal {
    bool finish = true;  ///< дойти до финиша
    int shards = 0;      ///< и/или собрать столько осколков памяти
};

/// Сколько секунд нужно идеальному боту, чтобы достичь цели. nullopt — цель недостижима.
/// farthest — докуда бот смог дойти (помогает найти место, где уровень непроходим).
std::optional<float> botSolve(const Level& level, PhysicsConfig physics, Abilities start, Goal goal = {},
                              float* farthest = nullptr) {
    struct Node {
        World world;
        int depth;
    };
    std::deque<Node> queue;
    std::set<Key> seen;
    queue.push_back({World(level.map, physics, start), 0});
    for (int i = 0; i < 30; ++i) queue.back().world.update(kFixedStep, {});  // встать на землю
    seen.insert(keyOf(queue.back().world));

    const auto reached = [&](const World& w) {
        return (!goal.finish || w.completed()) && w.shardsCollected() >= goal.shards;
    };

    int expansions = 0;
    while (!queue.empty() && expansions++ < kMaxExpansions) {
        const Node current = std::move(queue.front());
        queue.pop_front();
        for (int dir = -1; dir <= 1; ++dir) {
            for (int jump = 0; jump <= 1; ++jump) {
                for (int dash = 0; dash <= (current.world.abilities().dash ? 1 : 0); ++dash) {
                    World next = current.world;
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
                    if (reached(next)) {
                        return static_cast<float>((current.depth + 1) * kStepsPerAction) * kFixedStep;
                    }
                    // Бот играет аккуратно: смерть, потеря жизней и финиш без цели — тупиковые ветки.
                    if (next.completed() || next.player().dead || next.player().hp < next.player().maxHp) continue;
                    if (farthest) *farthest = std::max(*farthest, next.player().body.pos.x);
                    if (seen.insert(keyOf(next)).second) queue.push_back({std::move(next), current.depth + 1});
                }
            }
        }
    }
    return std::nullopt;
}

struct Case {
    const char* file;
    bool crude;
    Abilities start;  ///< с какими навыками игрок приходит на уровень по сюжету
};

// По сюжету: Алина учит двойному прыжку в конце crude_1, Егор рывку — в середине pretty_1.
const Case kCases[] = {
    {"crude_1.txt", true, {}},
    {"crude_2.txt", true, {.doubleJump = true}},
    {"crude_3.txt", true, {.doubleJump = true}},
    {"crude_4.txt", true, {.doubleJump = true}},
    {"pretty_1.txt", false, {.doubleJump = true}},
    {"pretty_2.txt", false, {.doubleJump = true, .dash = true}},
    {"pretty_3.txt", false, {.doubleJump = true, .dash = true}},
    {"pretty_4.txt", false, {.doubleJump = true, .dash = true}},
};

PhysicsConfig physicsOf(const Case& c) { return c.crude ? PhysicsConfig::crude() : PhysicsConfig::polished(); }

}  // namespace

TEST_CASE("уровни проходимы: бот доходит до финиша на каждом из восьми") {
    float total = 0.f;
    for (const Case& c : kCases) {
        const std::string file = c.file;
        float farthest = 0.f;
        const auto seconds = botSolve(load(c.file), physicsOf(c), c.start, {}, &farthest);
        if (!seconds) {
            FAIL_CHECK(file << ": бот застрял около столбца " << farthest);
            continue;
        }
        MESSAGE(file << ": идеальное прохождение за " << *seconds << " с");
        total += *seconds;
    }
    // Идеальный бот бежит без остановок; живой игрок тратит в несколько раз больше.
    MESSAGE("все уровни платформера: " << total << " с для бота");
    CHECK(total > 150.f);  // контент не «съёжился» случайно: игра рассчитана примерно на полчаса
}

TEST_CASE("осколки памяти достижимы: бот может собрать все на каждом уровне") {
    for (const Case& c : kCases) {
        const std::string file = c.file;
        CAPTURE(file);
        const Level level = load(c.file);
        const int shards = static_cast<int>(level.shards.size());
        if (shards == 0) continue;
        CHECK(botSolve(level, physicsOf(c), c.start, {.finish = false, .shards = shards}).has_value());
    }
}

TEST_CASE("без Алины стену в конце первого уровня не взять") {
    CHECK_FALSE(botSolve(loadWithout("crude_1.txt", '1'), PhysicsConfig::crude(), {}).has_value());
}

TEST_CASE("без Егора провал в красивом 2D не перелететь") {
    CHECK_FALSE(botSolve(loadWithout("pretty_1.txt", '2'), PhysicsConfig::polished(), {.doubleJump = true}).has_value());
}
