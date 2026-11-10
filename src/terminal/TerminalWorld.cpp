#include "terminal/TerminalWorld.hpp"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <queue>
#include <stdexcept>

namespace pix::terminal {

Point step(Point p, Dir d) {
    switch (d) {
        case Dir::Up: return {p.x, p.y - 1};
        case Dir::Down: return {p.x, p.y + 1};
        case Dir::Left: return {p.x - 1, p.y};
        case Dir::Right: return {p.x + 1, p.y};
    }
    return p;
}

int manhattan(Point a, Point b) { return std::abs(a.x - b.x) + std::abs(a.y - b.y); }
int chebyshev(Point a, Point b) { return std::max(std::abs(a.x - b.x), std::abs(a.y - b.y)); }

TerminalWorld::TerminalWorld(const std::vector<std::string>& mapLines, Config config, unsigned seed)
    : cfg_(config), rng_(seed) {
    height_ = static_cast<int>(mapLines.size());
    for (const auto& line : mapLines) width_ = std::max(width_, static_cast<int>(line.size()));
    if (width_ == 0 || height_ == 0) throw std::runtime_error("карта терминала пустая");

    // Всё, что не дописано в коротких строках, считается стеной.
    cells_.assign(static_cast<std::size_t>(width_ * height_), Cell::Wall);
    bool heroFound = false;
    bool portalFound = false;
    for (int y = 0; y < height_; ++y) {
        const auto& line = mapLines[static_cast<std::size_t>(y)];
        for (int x = 0; x < static_cast<int>(line.size()); ++x) {
            Cell c = Cell::Floor;
            switch (line[static_cast<std::size_t>(x)]) {
                case '#': c = Cell::Wall; break;
                case '%': c = Cell::Corrupt; break;
                case '@':
                    hero_ = {x, y};
                    heroFound = true;
                    break;
                case '>':
                    portal_ = {x, y};
                    portalFound = true;
                    break;
                default: break;
            }
            cells_[static_cast<std::size_t>(index({x, y}))] = c;
        }
    }
    if (!heroFound) throw std::runtime_error("на карте терминала нет героя '@'");
    if (!portalFound) portal_ = {width_ / 2, height_ / 2};

    brightness_ = cfg_.maxBrightness;
    gcTimer_ = cfg_.firstGcDelay;
    rebuildDistanceField();

    while (static_cast<int>(bits_.size()) < cfg_.targetBits && spawnBit()) {
    }
    for (int i = 0; i < cfg_.bugsBase; ++i) spawnBug();
}

// ---------------------------------------------------------------- запросы

bool TerminalWorld::inside(Point p) const { return p.x >= 0 && p.y >= 0 && p.x < width_ && p.y < height_; }

Cell TerminalWorld::cell(Point p) const {
    return inside(p) ? cells_[static_cast<std::size_t>(index(p))] : Cell::Wall;
}

bool TerminalWorld::walkable(Point p) const { return cell(p) == Cell::Floor; }

int TerminalWorld::bugAt(Point p) const {
    for (std::size_t i = 0; i < bugs_.size(); ++i) {
        if (bugs_[i].pos == p) return static_cast<int>(i);
    }
    return -1;
}

int TerminalWorld::distanceToHero(Point p) const {
    return inside(p) ? distance_[static_cast<std::size_t>(index(p))] : -1;
}

int TerminalWorld::bugCap() const { return cfg_.bugsBase + cfg_.bugsPerByte * bytes_; }

float TerminalWorld::bugStepInterval() const {
    return std::max(cfg_.bugStepMin, cfg_.bugStepBase - cfg_.bugStepPerByte * static_cast<float>(bytes_));
}

std::vector<Event> TerminalWorld::drainEvents() {
    std::vector<Event> out;
    out.swap(events_);
    return out;
}

// ---------------------------------------------------------------- команды игрока

void TerminalWorld::move(Dir d) {
    if (dead_ || escaped_) return;
    facing_ = d;
    const Point target = step(hero_, d);
    if (!walkable(target)) return;
    if (bugAt(target) >= 0) {
        hurt();  // врезался в жука — укусил
        return;
    }
    enterCell(target);
}

bool TerminalWorld::erase() {
    if (dead_ || escaped_ || !eraseUnlocked() || eraseCd_ > 0.f) return false;
    eraseCd_ = cfg_.eraseCooldown;
    lastMoveTime_ = time_;  // нажатие клавиши — тоже «использование»
    emit(EventType::Erased, hero_);
    for (auto it = bugs_.begin(); it != bugs_.end();) {
        if (chebyshev(it->pos, hero_) <= cfg_.eraseRadius) {
            emit(EventType::BugErased, it->pos);
            it = bugs_.erase(it);
        } else {
            ++it;
        }
    }
    return true;
}

bool TerminalWorld::dash() {
    if (dead_ || escaped_ || !dashUnlocked() || dashCd_ > 0.f) return false;
    const Point start = hero_;
    for (int i = 0; i < cfg_.dashLength && !escaped_; ++i) {
        const Point next = step(hero_, facing_);
        if (!walkable(next)) break;
        if (const int bug = bugAt(next); bug >= 0) {
            emit(EventType::BugErased, next);
            bugs_.erase(bugs_.begin() + bug);
        }
        enterCell(next);
    }
    if (hero_ == start) return false;
    dashCd_ = cfg_.dashCooldown;
    invuln_ = std::max(invuln_, 0.35f);
    emit(EventType::Dashed, hero_);
    return true;
}

void TerminalWorld::enterCell(Point p) {
    hero_ = p;
    lastMoveTime_ = time_;
    collectBitAt(p);
    if (portalOpen_ && hero_ == portal_) {
        escaped_ = true;
        emit(EventType::Escaped, hero_);
    }
    rebuildDistanceField();
}

void TerminalWorld::collectBitAt(Point p) {
    const auto it = std::find_if(bits_.begin(), bits_.end(), [p](const Bit& b) { return b.pos == p; });
    if (it == bits_.end()) return;

    const char value = it->value;
    bits_.erase(it);
    ++totalBits_;
    currentByte_.push_back(value);
    emit(EventType::BitCollected, p, value);

    if (currentByte_.size() < 8) return;
    currentByte_.clear();
    ++bytes_;
    emit(EventType::ByteCompleted, p, bytes_);
    if (bytes_ >= cfg_.bytesToWin && !portalOpen_) {
        portalOpen_ = true;
        emit(EventType::PortalOpened, portal_);
    }
}

void TerminalWorld::hurt() {
    if (invuln_ > 0.f || dead_ || escaped_) return;
    --brightness_;
    invuln_ = cfg_.invulnTime;
    emit(EventType::Hurt, hero_);
    if (brightness_ <= 0) {
        dead_ = true;
        emit(EventType::Died, hero_);
    }
}

// ---------------------------------------------------------------- время

void TerminalWorld::update(float dt) {
    if (dead_ || escaped_) return;
    time_ += dt;
    invuln_ = std::max(0.f, invuln_ - dt);
    eraseCd_ = std::max(0.f, eraseCd_ - dt);
    dashCd_ = std::max(0.f, dashCd_ - dt);

    updateBits(dt);
    updateBugs(dt);
    updateGc(dt);
}

void TerminalWorld::updateBits(float dt) {
    if (static_cast<int>(bits_.size()) >= cfg_.targetBits) {
        bitTimer_ = 0.f;
        return;
    }
    bitTimer_ += dt;
    if (bitTimer_ >= cfg_.bitRespawn) {
        bitTimer_ = 0.f;
        spawnBit();
    }
}

void TerminalWorld::updateBugs(float dt) {
    bugSpawnTimer_ += dt;
    if (bugSpawnTimer_ >= cfg_.bugSpawnInterval) {
        bugSpawnTimer_ = 0.f;
        if (static_cast<int>(bugs_.size()) < bugCap()) spawnBug();
    }

    const float interval = bugStepInterval();
    for (std::size_t i = 0; i < bugs_.size() && !dead_; ++i) {
        bugs_[i].moveTimer += dt;
        if (bugs_[i].moveTimer >= interval) {
            bugs_[i].moveTimer -= interval;
            stepBug(i);
        }
    }
}

void TerminalWorld::stepBug(std::size_t i) {
    const Point from = bugs_[i].pos;
    std::array<Dir, 4> dirs{Dir::Up, Dir::Down, Dir::Left, Dir::Right};
    std::shuffle(dirs.begin(), dirs.end(), rng_);

    const auto freeForBug = [&](Point p) { return walkable(p) && (p == hero_ || bugAt(p) < 0); };

    Point target = from;
    const int myDistance = distanceToHero(from);
    if (myDistance >= 0 && myDistance <= cfg_.chaseRadius) {
        // Погоня: шагаем в соседнюю клетку, которая ближе к герою.
        // Расстояния посчитаны заранее одним обходом в ширину — сразу для всех жуков.
        int best = myDistance;
        for (Dir d : dirs) {
            const Point n = step(from, d);
            const int nd = distanceToHero(n);
            if (nd >= 0 && nd < best && freeForBug(n)) {
                best = nd;
                target = n;
            }
        }
    } else {
        // Героя не видно — жук бродит как попало (иногда стоит на месте).
        std::uniform_int_distribution<int> chance(0, 9);
        if (chance(rng_) < 7) {
            for (Dir d : dirs) {
                const Point n = step(from, d);
                if (freeForBug(n) && n != hero_) {
                    target = n;
                    break;
                }
            }
        }
    }

    if (target == hero_) {
        hurt();
    } else {
        bugs_[i].pos = target;
    }
}

void TerminalWorld::updateGc(float dt) {
    switch (gcPhase_) {
        case GcPhase::Idle:
            gcTimer_ -= dt;
            if (gcTimer_ <= 0.f) {
                gcPhase_ = GcPhase::Warning;
                gcTimer_ = cfg_.gcWarning;
                emit(EventType::GcWarning, hero_);
            }
            break;
        case GcPhase::Warning:
            gcTimer_ -= dt;
            if (gcTimer_ <= 0.f) {
                gcPhase_ = GcPhase::Sweeping;
                gcProgress_ = 0.f;
                gcColumn_ = -1;
                emit(EventType::GcStarted, hero_);
            }
            break;
        case GcPhase::Sweeping: {
            gcProgress_ += dt * cfg_.gcColumnsPerSecond;
            const int target = std::min(width_ - 1, static_cast<int>(gcProgress_));
            while (gcColumn_ < target && !dead_) sweepColumn(++gcColumn_);
            // Стоишь в колонне сборщика и не двигаешься — тебя тоже соберут.
            if (hero_.x == gcColumn_ && heroIdle() && invuln_ <= 0.f && !dead_) {
                emit(EventType::GcHitHero, hero_);
                hurt();
            }
            if (gcColumn_ >= width_ - 1) {
                gcPhase_ = GcPhase::Idle;
                gcTimer_ = cfg_.gcInterval;
                gcColumn_ = -1;
            }
            break;
        }
    }
}

void TerminalWorld::sweepColumn(int column) {
    std::erase_if(bits_, [column](const Bit& b) { return b.pos.x == column; });
    for (auto it = bugs_.begin(); it != bugs_.end();) {
        if (it->pos.x == column) {
            emit(EventType::GcKilledBug, it->pos);
            it = bugs_.erase(it);
        } else {
            ++it;
        }
    }
}

// ---------------------------------------------------------------- появление объектов

template <typename Pred>
bool TerminalWorld::randomCell(Pred&& pred, Point& out) {
    std::vector<Point> candidates;
    for (int y = 0; y < height_; ++y) {
        for (int x = 0; x < width_; ++x) {
            const Point p{x, y};
            if (pred(p)) candidates.push_back(p);
        }
    }
    if (candidates.empty()) return false;
    std::uniform_int_distribution<std::size_t> pick(0, candidates.size() - 1);
    out = candidates[pick(rng_)];
    return true;
}

bool TerminalWorld::spawnBit() {
    const auto free = [this](Point p) {
        if (!walkable(p) || p == hero_ || p == portal_ || bugAt(p) >= 0) return false;
        return std::none_of(bits_.begin(), bits_.end(), [p](const Bit& b) { return b.pos == p; });
    };
    Point p;
    if (!randomCell(free, p)) return false;
    std::uniform_int_distribution<int> coin(0, 1);
    bits_.push_back({p, coin(rng_) == 0 ? '0' : '1'});
    return true;
}

bool TerminalWorld::spawnBug() {
    const auto nearCorrupt = [this](Point p) {
        for (Dir d : {Dir::Up, Dir::Down, Dir::Left, Dir::Right}) {
            if (cell(step(p, d)) == Cell::Corrupt) return true;
        }
        return false;
    };
    const auto freeFar = [this](Point p, int minDistance) {
        return walkable(p) && bugAt(p) < 0 && manhattan(p, hero_) >= minDistance;
    };

    Point p;
    // Сначала пытаемся вылезти из битого сектора, иначе — где-нибудь подальше от героя.
    if (randomCell([&](Point c) { return nearCorrupt(c) && freeFar(c, 6); }, p) ||
        randomCell([&](Point c) { return freeFar(c, 10); }, p)) {
        bugs_.push_back({p, 0.f});
        return true;
    }
    return false;
}

void TerminalWorld::rebuildDistanceField() {
    distance_.assign(cells_.size(), -1);
    std::queue<Point> queue;
    distance_[static_cast<std::size_t>(index(hero_))] = 0;
    queue.push(hero_);
    while (!queue.empty()) {
        const Point p = queue.front();
        queue.pop();
        const int d = distance_[static_cast<std::size_t>(index(p))];
        for (Dir dir : {Dir::Up, Dir::Down, Dir::Left, Dir::Right}) {
            const Point n = step(p, dir);
            if (!walkable(n)) continue;
            auto& nd = distance_[static_cast<std::size_t>(index(n))];
            if (nd >= 0) continue;
            nd = d + 1;
            queue.push(n);
        }
    }
}

}  // namespace pix::terminal
