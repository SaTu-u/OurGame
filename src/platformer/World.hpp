#pragma once

// Мир платформера — общая логика для стрёмного и красивого 2D.
// Мир ничего не рисует и не играет звуки: он только сообщает о том, что случилось,
// через события (WorldEvent). Рендереры решают, как это показать:
// кривым квадратом или облаком светящихся частиц.

#include <vector>

#include "platformer/Math.hpp"
#include "platformer/Physics.hpp"
#include "platformer/TileMap.hpp"

namespace pix::platformer {

struct Input {
    bool left = false;
    bool right = false;
    bool jumpHeld = false;
    bool jumpPressed = false;  ///< нажат именно в этом кадре
    bool dashPressed = false;
};

struct Abilities {
    bool doubleJump = false;  ///< этому учит Алина
    bool dash = false;        ///< этому учит Егор
};

enum class EventType {
    Jump,
    AirJump,
    Land,
    Dash,
    Spring,
    Pickup,
    Stomp,
    DashKill,
    Hurt,
    Death,
    Respawn,
    Checkpoint,
    BuddyMet,    ///< value — номер друга (1 — Алина, 2 — Егор)
    StoryPoint,  ///< value — номер сюжетной точки «!» на уровне (слева направо, с нуля)
    Shard,       ///< value — номер осколка памяти «?»
    LevelComplete,
};

struct WorldEvent {
    EventType type;
    Vec2 pos;
    int value = 0;
};

struct Player {
    Body body;
    int hp = 3;
    int maxHp = 3;
    int facing = 1;  ///< 1 — смотрит вправо, -1 — влево
    float invuln = 0.f;
    float coyote = 0.f;
    float jumpBuffer = 0.f;
    float dashTime = 0.f;
    float dashCooldown = 0.f;
    int airJumps = 0;
    bool jumpCut = true;
    bool dead = false;
    float respawnTimer = 0.f;
    Vec2 checkpoint;

    [[nodiscard]] bool dashing() const { return dashTime > 0.f; }
};

struct Enemy {
    Body body;
    float dir = -1.f;
    bool alive = true;
};

struct Pickup {
    Vec2 pos;  ///< центр
    bool taken = false;
};

struct Buddy {
    Vec2 pos;  ///< середина нижнего края
    int id = 1;
    bool met = false;
};

struct Checkpoint {
    Rect area;
    bool active = false;
};

struct Spring {
    Rect area;
    float squash = 0.f;  ///< >0 — пружина только что сработала (для анимации)
};

/// Сюжетная точка «!»: невидимая черта во всю высоту уровня — мимо не пройти.
struct StoryPoint {
    float x = 0.f;
    bool fired = false;
};

/// Осколок памяти «?»: необязательная находка с кусочком истории мира.
struct Shard {
    Vec2 pos;  ///< центр
    bool taken = false;
};

class World {
public:
    World(const TileMap& map, PhysicsConfig config, Abilities abilities);

    void update(float dt, const Input& input);
    std::vector<WorldEvent> drainEvents();

    [[nodiscard]] const TileMap& map() const { return map_; }
    [[nodiscard]] const PhysicsConfig& config() const { return cfg_; }
    [[nodiscard]] const Player& player() const { return player_; }
    [[nodiscard]] const std::vector<Enemy>& enemies() const { return enemies_; }
    [[nodiscard]] const std::vector<Pickup>& pickups() const { return pickups_; }
    [[nodiscard]] const std::vector<Buddy>& buddies() const { return buddies_; }
    [[nodiscard]] const std::vector<Checkpoint>& checkpoints() const { return checkpoints_; }
    [[nodiscard]] const std::vector<Spring>& springs() const { return springs_; }
    [[nodiscard]] const std::vector<StoryPoint>& storyPoints() const { return storyPoints_; }
    [[nodiscard]] const std::vector<Shard>& shards() const { return shards_; }
    [[nodiscard]] bool hasFinish() const { return hasFinish_; }
    [[nodiscard]] Rect finish() const { return finish_; }
    [[nodiscard]] const Abilities& abilities() const { return abilities_; }
    [[nodiscard]] bool completed() const { return completed_; }
    [[nodiscard]] int pickupsCollected() const { return pickupsCollected_; }
    [[nodiscard]] int shardsCollected() const { return shardsCollected_; }
    [[nodiscard]] int deaths() const { return deaths_; }
    [[nodiscard]] float time() const { return time_; }

    /// Завершить уровень немедленно (режим показа --demo).
    void forceComplete() { completed_ = true; }

    // для тестов
    Player& mutablePlayer() { return player_; }

private:
    void updatePlayer(float dt, const Input& in);
    void updateEnemies(float dt);
    void updateSprings(float dt);
    void checkTriggers();
    void hurtPlayer(float sourceX);
    void killPlayer();
    void respawn();
    [[nodiscard]] bool isStomp(const Rect& target) const;
    void bounceOff();
    void emit(EventType type, Vec2 pos, int value = 0) { events_.push_back({type, pos, value}); }

    TileMap map_;
    PhysicsConfig cfg_;
    Abilities abilities_;

    Player player_;
    std::vector<Enemy> enemies_;
    std::vector<Pickup> pickups_;
    std::vector<Buddy> buddies_;
    std::vector<Checkpoint> checkpoints_;
    std::vector<Spring> springs_;
    std::vector<StoryPoint> storyPoints_;
    std::vector<Shard> shards_;
    Rect finish_;
    bool hasFinish_ = false;

    bool completed_ = false;
    int pickupsCollected_ = 0;
    int shardsCollected_ = 0;
    int deaths_ = 0;
    float time_ = 0.f;
    std::vector<WorldEvent> events_;
};

/// Шаг фиксированной физики. Фиксированный шаг = одинаковое поведение на любом FPS.
inline constexpr float kFixedStep = 1.f / 120.f;

}  // namespace pix::platformer
