#include "platformer/World.hpp"

#include <algorithm>
#include <cmath>

namespace pix::platformer {

namespace {

constexpr Vec2 kPlayerSize{0.75f, 0.75f};
constexpr Vec2 kEnemySize{0.8f, 0.6f};
constexpr float kRespawnDelay = 0.9f;
constexpr float kPickupRadius = 0.7f;
constexpr float kShardRadius = 0.9f;
constexpr float kBuddyRadius = 1.3f;

/// Ставит тело так, чтобы оно стояло на дне клетки (x, y) по центру.
Vec2 standOn(int x, int y, Vec2 size) {
    return {static_cast<float>(x) + (1.f - size.x) * 0.5f, static_cast<float>(y) + 1.f - size.y};
}

Rect tallTrigger(int x, int y) { return {static_cast<float>(x), static_cast<float>(y) - 1.f, 1.f, 2.f}; }

Vec2 cellCenter(int x, int y) { return {static_cast<float>(x) + 0.5f, static_cast<float>(y) + 0.5f}; }

}  // namespace

World::World(const TileMap& map, PhysicsConfig config, Abilities abilities)
    : map_(map), cfg_(config), abilities_(abilities) {
    for (const Spawn& s : map_.spawns()) {
        switch (s.symbol) {
            case 'P':
                player_.body.size = kPlayerSize;
                player_.body.pos = standOn(s.x, s.y, kPlayerSize);
                player_.checkpoint = player_.body.pos;
                break;
            case 'x': {
                Enemy e;
                e.body.size = kEnemySize;
                e.body.pos = standOn(s.x, s.y, kEnemySize);
                enemies_.push_back(e);
                break;
            }
            case '*': pickups_.push_back({cellCenter(s.x, s.y), false}); break;
            case 'F':
                finish_ = tallTrigger(s.x, s.y);
                hasFinish_ = true;
                break;
            case 'C': checkpoints_.push_back({tallTrigger(s.x, s.y), false}); break;
            case 'S':
                // Пружина — нижняя половина клетки.
                springs_.push_back({{static_cast<float>(s.x) + 0.1f, static_cast<float>(s.y) + 0.5f, 0.8f, 0.5f}, 0.f});
                break;
            case '1':
            case '2':
                buddies_.push_back({{static_cast<float>(s.x) + 0.5f, static_cast<float>(s.y) + 1.f}, s.symbol - '0', false});
                break;
            default: break;
        }
    }
    // Сюжетные точки и осколки нумеруются слева направо — в том же порядке, что @talk и @shard в файле уровня.
    for (const Spawn& s : map_.all('!')) storyPoints_.push_back({static_cast<float>(s.x), false});
    for (const Spawn& s : map_.all('?')) shards_.push_back({cellCenter(s.x, s.y), false});

    // Друга, которого уже встретили раньше, второй раз не встречаем.
    for (auto& b : buddies_) {
        if ((b.id == 1 && abilities_.doubleJump) || (b.id == 2 && abilities_.dash)) b.met = true;
    }
}

std::vector<WorldEvent> World::drainEvents() {
    std::vector<WorldEvent> out;
    out.swap(events_);
    return out;
}

void World::update(float dt, const Input& input) {
    time_ += dt;
    updatePlayer(dt, input);
    updateEnemies(dt);
    updateSprings(dt);
    checkTriggers();
}

// ---------------------------------------------------------------- игрок

void World::updatePlayer(float dt, const Input& in) {
    Player& p = player_;
    if (p.dead) {
        p.respawnTimer -= dt;
        if (p.respawnTimer <= 0.f) respawn();
        return;
    }

    p.invuln = std::max(0.f, p.invuln - dt);
    p.dashCooldown = std::max(0.f, p.dashCooldown - dt);

    const float dir = static_cast<float>((in.right ? 1 : 0) - (in.left ? 1 : 0));
    if (dir != 0.f) p.facing = dir > 0.f ? 1 : -1;

    if (p.body.onGround) {
        p.coyote = cfg_.coyoteTime + dt;
        p.airJumps = abilities_.doubleJump ? 1 : 0;
    }
    if (in.jumpPressed) p.jumpBuffer = cfg_.jumpBufferTime + dt;

    // Рывок
    if (in.dashPressed && abilities_.dash && p.dashCooldown <= 0.f && !p.dashing()) {
        p.dashTime = cfg_.dashDuration;
        p.dashCooldown = cfg_.dashCooldown;
        emit(EventType::Dash, p.body.center(), p.facing);
    }

    if (p.dashing()) {
        p.dashTime -= dt;
        p.body.vel = {static_cast<float>(p.facing) * cfg_.dashSpeed, 0.f};
    } else {
        const float target = dir * cfg_.runSpeed;
        float accel = p.body.onGround ? cfg_.groundAccel : cfg_.airAccel;
        if (dir == 0.f && p.body.onGround) accel = cfg_.groundFriction;
        p.body.vel.x = approach(p.body.vel.x, target, accel * dt);
        p.body.vel.y = std::min(p.body.vel.y + cfg_.gravity * dt, cfg_.maxFallSpeed);

        // Прыжок: с земли (или «койотский» — сразу после края), иначе — второй в воздухе.
        if (p.jumpBuffer > 0.f) {
            if (p.coyote > 0.f) {
                p.body.vel.y = -cfg_.jumpSpeed;
                p.coyote = 0.f;
                p.jumpBuffer = 0.f;
                p.jumpCut = false;
                emit(EventType::Jump, p.body.center());
            } else if (p.airJumps > 0) {
                --p.airJumps;
                p.body.vel.y = -cfg_.jumpSpeed * cfg_.airJumpFactor;
                p.jumpBuffer = 0.f;
                p.jumpCut = false;
                emit(EventType::AirJump, p.body.center());
            }
        }
        // Отпустил кнопку раньше — прыжок ниже.
        if (!in.jumpHeld && !p.jumpCut && p.body.vel.y < 0.f) {
            p.body.vel.y *= cfg_.jumpCutMultiplier;
            p.jumpCut = true;
        }
    }

    const bool wasOnGround = p.body.onGround;
    const float fallSpeed = p.body.vel.y;
    moveAndCollide(p.body, map_, dt);
    if (!wasOnGround && p.body.onGround) emit(EventType::Land, p.body.center(), static_cast<int>(fallSpeed));

    p.coyote = std::max(0.f, p.coyote - dt);
    p.jumpBuffer = std::max(0.f, p.jumpBuffer - dt);

    if (touchesSpikes(p.body.rect(), map_)) hurtPlayer(p.body.center().x - static_cast<float>(p.facing));
    if (p.body.pos.y > static_cast<float>(map_.height()) + 3.f) killPlayer();
}

void World::hurtPlayer(float sourceX) {
    Player& p = player_;
    if (p.dead || p.invuln > 0.f || p.dashing()) return;
    --p.hp;
    p.invuln = cfg_.invulnTime;
    emit(EventType::Hurt, p.body.center(), p.hp);
    if (p.hp <= 0) {
        killPlayer();
        return;
    }
    // Отбрасывает от того, обо что ударился.
    const float away = p.body.center().x < sourceX ? -1.f : 1.f;
    p.body.vel = {away * 9.f, -12.f};
    p.jumpCut = true;
}

void World::killPlayer() {
    Player& p = player_;
    if (p.dead) return;
    p.dead = true;
    p.hp = 0;
    p.respawnTimer = kRespawnDelay;
    ++deaths_;
    emit(EventType::Death, p.body.center());
}

void World::respawn() {
    Player& p = player_;
    p.dead = false;
    p.hp = p.maxHp;
    p.body.pos = p.checkpoint;
    p.body.vel = {};
    p.invuln = cfg_.invulnTime;
    p.dashTime = 0.f;
    p.jumpCut = true;
    emit(EventType::Respawn, p.body.center());
}

bool World::isStomp(const Rect& target) const {
    const Body& b = player_.body;
    return b.vel.y > 0.f && b.pos.y + b.size.y - target.top() < 0.5f;
}

void World::bounceOff() {
    player_.body.vel.y = -cfg_.jumpSpeed * cfg_.stompBounce;
    player_.jumpCut = true;
    player_.airJumps = abilities_.doubleJump ? 1 : 0;
}

// ---------------------------------------------------------------- жуки

void World::updateEnemies(float dt) {
    for (Enemy& e : enemies_) {
        if (!e.alive) continue;
        e.body.vel.x = e.dir * cfg_.enemySpeed;
        e.body.vel.y = std::min(e.body.vel.y + cfg_.gravity * dt, cfg_.maxFallSpeed);
        moveAndCollide(e.body, map_, dt);

        if (e.body.hitWall) {
            e.dir = -e.dir;
        } else if (e.body.onGround) {
            // Жуки не дураки: с края платформы не падают и на шипы не лезут.
            const Rect r = e.body.rect();
            const Vec2 probe{e.dir > 0.f ? r.right() + 0.05f : r.left() - 0.05f, r.bottom() + 0.1f};
            const bool spikeAhead = map_.at(static_cast<int>(std::floor(probe.x)),
                                            static_cast<int>(std::floor(r.bottom() - 0.5f))) == Tile::Spike;
            if (!solidAt(map_, probe) || spikeAhead) e.dir = -e.dir;
        }
        if (e.body.pos.y > static_cast<float>(map_.height()) + 3.f) e.alive = false;

        if (player_.dead || !player_.body.rect().intersects(e.body.rect())) continue;
        if (player_.dashing()) {
            e.alive = false;
            emit(EventType::DashKill, e.body.center());
        } else if (isStomp(e.body.rect())) {
            e.alive = false;
            bounceOff();
            emit(EventType::Stomp, e.body.center());
        } else {
            hurtPlayer(e.body.center().x);
        }
    }
}

// ---------------------------------------------------------------- пружины

void World::updateSprings(float dt) {
    Player& p = player_;
    for (Spring& spring : springs_) {
        spring.squash = std::max(0.f, spring.squash - dt * 4.f);
        // Срабатывает, когда герой падает на неё или идёт по земле (но не когда летит вверх).
        if (p.dead || p.body.vel.y < 0.f || !spring.area.intersects(p.body.rect())) continue;
        p.body.vel.y = -cfg_.springSpeed;
        p.body.onGround = false;
        p.coyote = 0.f;
        p.jumpCut = true;  // высота от пружины не зависит от кнопки прыжка
        p.airJumps = abilities_.doubleJump ? 1 : 0;
        p.dashTime = 0.f;
        spring.squash = 1.f;
        emit(EventType::Spring, spring.area.center());
    }
}

// ---------------------------------------------------------------- триггеры

void World::checkTriggers() {
    if (player_.dead) return;
    const Rect pr = player_.body.rect();
    const Vec2 pc = pr.center();

    for (Pickup& pickup : pickups_) {
        if (!pickup.taken && distance(pickup.pos, pc) < kPickupRadius) {
            pickup.taken = true;
            ++pickupsCollected_;
            emit(EventType::Pickup, pickup.pos);
        }
    }

    for (std::size_t i = 0; i < shards_.size(); ++i) {
        if (shards_[i].taken || distance(shards_[i].pos, pc) > kShardRadius) continue;
        shards_[i].taken = true;
        ++shardsCollected_;
        emit(EventType::Shard, shards_[i].pos, static_cast<int>(i));
    }

    for (Buddy& buddy : buddies_) {
        if (buddy.met || distance(buddy.pos, {pc.x, pr.bottom()}) > kBuddyRadius) continue;
        buddy.met = true;
        if (buddy.id == 1) abilities_.doubleJump = true;
        if (buddy.id == 2) abilities_.dash = true;
        emit(EventType::BuddyMet, buddy.pos, buddy.id);
    }

    // Сюжетная точка срабатывает, когда герой пересёк её по горизонтали — на любой высоте.
    for (std::size_t i = 0; i < storyPoints_.size(); ++i) {
        if (storyPoints_[i].fired || pc.x < storyPoints_[i].x) continue;
        storyPoints_[i].fired = true;
        emit(EventType::StoryPoint, pc, static_cast<int>(i));
    }

    for (std::size_t i = 0; i < checkpoints_.size(); ++i) {
        if (checkpoints_[i].active || !checkpoints_[i].area.intersects(pr)) continue;
        for (auto& c : checkpoints_) c.active = false;
        checkpoints_[i].active = true;
        const Rect& a = checkpoints_[i].area;
        player_.checkpoint = {a.center().x - player_.body.size.x * 0.5f, a.bottom() - player_.body.size.y};
        emit(EventType::Checkpoint, a.center());
    }

    if (hasFinish_ && !completed_ && finish_.intersects(pr)) {
        completed_ = true;
        emit(EventType::LevelComplete, finish_.center());
    }
}

}  // namespace pix::platformer
