#include "platformer/World.hpp"

#include <algorithm>
#include <cmath>

namespace pix::platformer {

namespace {

constexpr Vec2 kPlayerSize{0.75f, 0.75f};
constexpr Vec2 kEnemySize{0.8f, 0.6f};
constexpr Vec2 kBossSize{2.6f, 2.f};
constexpr Vec2 kProjectileSize{0.45f, 0.45f};
constexpr float kRespawnDelay = 0.9f;
constexpr float kPickupRadius = 0.7f;
constexpr float kBuddyRadius = 1.3f;
constexpr float kBossWakeDistance = 11.f;

/// Ставит тело так, чтобы оно стояло на дне клетки (x, y) по центру.
Vec2 standOn(int x, int y, Vec2 size) {
    return {static_cast<float>(x) + (1.f - size.x) * 0.5f, static_cast<float>(y) + 1.f - size.y};
}

Rect tallTrigger(int x, int y) { return {static_cast<float>(x), static_cast<float>(y) - 1.f, 1.f, 2.f}; }

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
            case '*':
                pickups_.push_back({{static_cast<float>(s.x) + 0.5f, static_cast<float>(s.y) + 0.5f}, false});
                break;
            case 'F':
                finish_ = tallTrigger(s.x, s.y);
                hasFinish_ = true;
                break;
            case 'C': checkpoints_.push_back({tallTrigger(s.x, s.y), false}); break;
            case '1':
            case '2':
                buddies_.push_back({{static_cast<float>(s.x) + 0.5f, static_cast<float>(s.y) + 1.f}, s.symbol - '0', false});
                break;
            case 'B':
                boss_.present = true;
                boss_.body.size = kBossSize;
                boss_.body.pos = {static_cast<float>(s.x) + 0.5f - kBossSize.x * 0.5f, static_cast<float>(s.y)};
                boss_.hoverY = boss_.body.pos.y;
                break;
            default: break;
        }
    }
    // Кента, которого уже встретили раньше, второй раз не встречаем.
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
    updateBoss(dt);
    updateProjectiles(dt);
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
    projectiles_.clear();
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
            // Жуки не дураки: с края платформы не падают.
            const Rect r = e.body.rect();
            const Vec2 probe{e.dir > 0.f ? r.right() + 0.05f : r.left() - 0.05f, r.bottom() + 0.1f};
            if (!solidAt(map_, probe) || map_.at(static_cast<int>(std::floor(probe.x)),
                                                 static_cast<int>(std::floor(r.bottom() - 0.5f))) == Tile::Spike) {
                e.dir = -e.dir;
            }
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

// ---------------------------------------------------------------- босс

void World::updateBoss(float dt) {
    if (!boss_.present || boss_.state == BossState::Dead) return;
    Boss& b = boss_;
    b.hurtFlash = std::max(0.f, b.hurtFlash - dt);
    const int damage = b.maxHp - b.hp;  // чем больше урона, тем злее босс
    const Vec2 target = player_.body.center();

    switch (b.state) {
        case BossState::Dormant:
            if (!player_.dead && std::abs(target.x - b.body.center().x) < kBossWakeDistance) {
                b.state = BossState::Hover;
                b.timer = 3.f;
                b.shootTimer = 1.5f;
                emit(EventType::BossAwake, b.body.center());
            }
            return;
        case BossState::Hover: {
            const float speed = 5.f + 1.5f * static_cast<float>(damage);
            b.body.vel.x = approach(b.body.vel.x, (target.x - b.body.center().x) * 2.f, 30.f * dt);
            b.body.vel.x = std::clamp(b.body.vel.x, -speed, speed);
            b.body.vel.y = (b.hoverY + std::sin(time_ * 3.f) * 0.3f - b.body.pos.y) * 4.f;
            moveAndCollide(b.body, map_, dt);
            b.shootTimer -= dt;
            if (b.shootTimer <= 0.f) {
                b.shootTimer = std::max(0.45f, 1.1f - 0.18f * static_cast<float>(damage));
                Projectile shot;
                shot.body.size = kProjectileSize;
                shot.body.pos = {b.body.center().x - kProjectileSize.x * 0.5f, b.body.rect().bottom()};
                shot.body.vel = {b.body.vel.x * 0.3f, 2.f};
                projectiles_.push_back(shot);
                emit(EventType::BossShoot, shot.body.center());
            }
            b.timer -= dt;
            if (b.timer <= 0.f) {
                b.state = BossState::Telegraph;
                b.timer = std::max(0.35f, 0.7f - 0.1f * static_cast<float>(damage));
                b.body.vel = {};
            }
            break;
        }
        case BossState::Telegraph:
            b.timer -= dt;
            if (b.timer <= 0.f) {
                b.state = BossState::Slam;
                b.body.vel = {0.f, 30.f};
            }
            break;
        case BossState::Slam:
            b.body.vel = {0.f, 30.f};
            moveAndCollide(b.body, map_, dt);
            if (b.body.onGround) {
                b.state = BossState::Stunned;
                b.timer = 2.2f;
                emit(EventType::BossSlam, {b.body.center().x, b.body.rect().bottom()});
            }
            break;
        case BossState::Stunned:
            b.timer -= dt;
            if (b.timer <= 0.f) {
                b.state = BossState::Rise;
            }
            break;
        case BossState::Rise:
            b.body.vel = {0.f, -8.f};
            moveAndCollide(b.body, map_, dt);
            if (b.body.pos.y <= b.hoverY || b.body.hitCeiling) {
                b.body.pos.y = std::max(b.body.pos.y, b.hoverY);
                b.state = BossState::Hover;
                b.timer = std::max(1.8f, 3.f - 0.3f * static_cast<float>(damage));
            }
            break;
        case BossState::Dead: return;
    }

    if (player_.dead || !player_.body.rect().intersects(b.body.rect())) return;

    if (b.state == BossState::Stunned) {
        // Оглушённого босса можно бить: прыжком сверху или рывком.
        const bool stomp = isStomp(b.body.rect());
        if (stomp || player_.dashing()) {
            if (stomp) bounceOff();
            --b.hp;
            b.hurtFlash = 0.4f;
            emit(EventType::BossHit, b.body.center(), b.hp);
            if (b.hp <= 0) {
                b.state = BossState::Dead;
                completed_ = true;
                projectiles_.clear();
                emit(EventType::BossDefeated, b.body.center());
            } else {
                b.state = BossState::Rise;
            }
        }
        return;
    }
    hurtPlayer(b.body.center().x);
}

void World::updateProjectiles(float dt) {
    for (Projectile& shot : projectiles_) {
        if (!shot.alive) continue;
        shot.body.vel.y = std::min(shot.body.vel.y + cfg_.gravity * 0.4f * dt, cfg_.maxFallSpeed);
        moveAndCollide(shot.body, map_, dt);
        if (shot.body.onGround || shot.body.hitWall || shot.body.pos.y > static_cast<float>(map_.height())) {
            shot.alive = false;
            emit(EventType::ProjectileBurst, shot.body.center());
            continue;
        }
        if (!player_.dead && player_.body.rect().intersects(shot.body.rect())) {
            shot.alive = false;
            emit(EventType::ProjectileBurst, shot.body.center());
            hurtPlayer(shot.body.center().x);
        }
    }
    std::erase_if(projectiles_, [](const Projectile& s) { return !s.alive; });
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

    for (Buddy& buddy : buddies_) {
        if (buddy.met || distance(buddy.pos, {pc.x, pr.bottom()}) > kBuddyRadius) continue;
        buddy.met = true;
        if (buddy.id == 1) abilities_.doubleJump = true;
        if (buddy.id == 2) abilities_.dash = true;
        emit(EventType::BuddyMet, buddy.pos, buddy.id);
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
