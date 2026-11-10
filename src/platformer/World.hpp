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
    bool doubleJump = false;  ///< от Глюка
    bool dash = false;        ///< от Шейдера
};

enum class EventType {
    Jump,
    AirJump,
    Land,
    Dash,
    Pickup,
    Stomp,
    DashKill,
    Hurt,
    Death,
    Respawn,
    Checkpoint,
    BuddyMet,  ///< value — номер кента (1 — Глюк, 2 — Шейдер)
    LevelComplete,
    BossAwake,
    BossShoot,
    BossSlam,
    BossHit,
    BossDefeated,
    ProjectileBurst,
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

struct Projectile {
    Body body;
    bool alive = true;
};

enum class BossState { Dormant, Hover, Telegraph, Slam, Stunned, Rise, Dead };

struct Boss {
    bool present = false;
    Body body;
    BossState state = BossState::Dormant;
    int hp = 4;
    int maxHp = 4;
    float timer = 0.f;
    float shootTimer = 0.f;
    float hoverY = 0.f;
    float hurtFlash = 0.f;
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
    [[nodiscard]] const std::vector<Projectile>& projectiles() const { return projectiles_; }
    [[nodiscard]] const Boss& boss() const { return boss_; }
    [[nodiscard]] bool hasFinish() const { return hasFinish_; }
    [[nodiscard]] Rect finish() const { return finish_; }
    [[nodiscard]] const Abilities& abilities() const { return abilities_; }
    [[nodiscard]] bool completed() const { return completed_; }
    [[nodiscard]] int pickupsCollected() const { return pickupsCollected_; }
    [[nodiscard]] int deaths() const { return deaths_; }
    [[nodiscard]] float time() const { return time_; }

    // для тестов
    Player& mutablePlayer() { return player_; }
    Boss& mutableBoss() { return boss_; }

private:
    void updatePlayer(float dt, const Input& in);
    void updateEnemies(float dt);
    void updateBoss(float dt);
    void updateProjectiles(float dt);
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
    std::vector<Projectile> projectiles_;
    Boss boss_;
    Rect finish_;
    bool hasFinish_ = false;

    bool completed_ = false;
    int pickupsCollected_ = 0;
    int deaths_ = 0;
    float time_ = 0.f;
    std::vector<WorldEvent> events_;
};

/// Шаг фиксированной физики. Фиксированный шаг = одинаковое поведение на любом FPS.
inline constexpr float kFixedStep = 1.f / 120.f;

}  // namespace pix::platformer
