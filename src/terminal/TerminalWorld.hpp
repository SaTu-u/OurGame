#pragma once

// Логика первой стадии — «Терминал». Здесь нет ни одного вывода на экран:
// мир только считает, а TerminalStage его рисует. Поэтому мир легко тестировать.
//
// Правила:
//   * Тимур — пиксель (курсор), ходит по клеткам;
//   * биты 0/1 — остатки данных мира. 8 бит = 1 байт, байты открывают навыки и портал;
//   * жуки-баги (ж) чуют героя и идут к нему по кратчайшему пути;
//   * Обнуление — волна Пустоты, которая проходит по экрану и стирает всё,
//     что стоит на месте: жуков, биты и героя, если тот замер;
//   * собрал нужное число байт — открывается портал «>» в следующий сектор.

#include <cstdint>
#include <random>
#include <string>
#include <vector>

namespace pix::terminal {

struct Point {
    int x = 0;
    int y = 0;
    bool operator==(const Point&) const = default;
};

enum class Dir { Up, Down, Left, Right };

Point step(Point p, Dir d);
int manhattan(Point a, Point b);
int chebyshev(Point a, Point b);

enum class Cell : std::uint8_t {
    Floor,
    Wall,     ///< '#'
    Corrupt,  ///< '%' — битый сектор, из него вылезают жуки
};

struct Bug {
    Point pos;
    float moveTimer = 0.f;
};

struct Bit {
    Point pos;
    char value = '0';
};

enum class EventType {
    BitCollected,   ///< value — символ бита ('0' или '1')
    ByteCompleted,  ///< value — сколько байт собрано
    Hurt,
    Died,
    Erased,     ///< использован Backspace
    BugErased,  ///< жук стёрт (Backspace или рывком)
    Dashed,
    WipeWarning,  ///< Обнуление скоро начнётся
    WipeStarted,
    WipeKilledBug,
    WipeHitHero,
    PortalOpened,
    Escaped,
};

struct Event {
    EventType type;
    Point pos{};
    int value = 0;
};

enum class WipePhase { Idle, Warning, Sweeping };

/// Все «магические числа» баланса — в одном месте.
struct Config {
    int bytesToWin = 3;
    int maxBrightness = 3;
    float invulnTime = 1.2f;
    float moveInterval = 0.085f;  ///< не чаще одного шага за это время: зажатая клавиша не даёт «телепорт»
    int visionRadius = 0;         ///< >0 — тьма: стены и жуков видно только рядом (биты светятся всегда)

    int targetBits = 10;  ///< сколько бит одновременно лежит на карте
    float bitRespawn = 0.9f;

    int bugsBase = 3;
    int bugsPerByte = 2;
    float bugSpawnInterval = 5.f;
    float bugStepBase = 0.5f;
    float bugStepPerByte = 0.07f;
    float bugStepMin = 0.2f;
    int chaseRadius = 9;

    float firstWipeDelay = 14.f;
    float wipeInterval = 22.f;
    float wipeWarning = 3.f;
    float wipeColumnsPerSecond = 22.f;
    float idleThreshold = 0.8f;  ///< столько секунд без движения — и тебя сотрёт

    int eraseUnlockBytes = 1;
    int eraseRadius = 2;
    float eraseCooldown = 1.5f;

    int dashUnlockBytes = 2;
    int dashLength = 5;
    float dashCooldown = 2.f;
};

class TerminalWorld {
public:
    /// mapLines — карта: '#' стена, '%' битый сектор, '@' старт героя, '>' портал.
    /// Бросает std::runtime_error, если на карте нет героя.
    explicit TerminalWorld(const std::vector<std::string>& mapLines, Config config = {}, unsigned seed = 1);

    // --- команды игрока ---
    void move(Dir d);
    /// Backspace: стирает жуков вокруг героя. false — навык ещё не открыт или перезаряжается.
    bool erase();
    /// Рывок (Tab) по направлению взгляда, сквозь жуков. false — нельзя.
    bool dash();

    void update(float dt);
    /// Забрать события, накопившиеся с прошлого вызова.
    std::vector<Event> drainEvents();

    // --- состояние для отрисовки ---
    [[nodiscard]] int width() const { return width_; }
    [[nodiscard]] int height() const { return height_; }
    [[nodiscard]] Cell cell(Point p) const;
    [[nodiscard]] Point hero() const { return hero_; }
    [[nodiscard]] Dir facing() const { return facing_; }
    [[nodiscard]] int brightness() const { return brightness_; }
    [[nodiscard]] const Config& config() const { return cfg_; }
    [[nodiscard]] bool heroInvulnerable() const { return invuln_ > 0.f; }
    [[nodiscard]] bool heroIdle() const { return time_ - lastMoveTime_ >= cfg_.idleThreshold; }

    [[nodiscard]] int totalBits() const { return totalBits_; }
    [[nodiscard]] int bytes() const { return bytes_; }
    [[nodiscard]] const std::string& currentByte() const { return currentByte_; }

    [[nodiscard]] bool eraseUnlocked() const { return bytes_ >= cfg_.eraseUnlockBytes; }
    [[nodiscard]] bool dashUnlocked() const { return bytes_ >= cfg_.dashUnlockBytes; }
    [[nodiscard]] float eraseCooldown() const { return eraseCd_; }
    [[nodiscard]] float dashCooldown() const { return dashCd_; }

    [[nodiscard]] bool portalOpen() const { return portalOpen_; }
    [[nodiscard]] Point portal() const { return portal_; }
    [[nodiscard]] bool dead() const { return dead_; }
    [[nodiscard]] bool escaped() const { return escaped_; }

    [[nodiscard]] const std::vector<Bug>& bugs() const { return bugs_; }
    [[nodiscard]] const std::vector<Bit>& bits() const { return bits_; }

    [[nodiscard]] WipePhase wipePhase() const { return wipePhase_; }
    [[nodiscard]] float wipeTimer() const { return wipeTimer_; }
    [[nodiscard]] int wipeColumn() const { return wipeColumn_; }
    [[nodiscard]] float time() const { return time_; }

    /// Видна ли клетка герою (во тьме — только рядом с ним).
    [[nodiscard]] bool visible(Point p) const;

    /// Завершить сектор немедленно (режим показа --demo).
    void forceEscape() { escaped_ = true; }

    /// Расстояние от клетки до героя по проходимым клеткам (-1 — недостижимо).
    [[nodiscard]] int distanceToHero(Point p) const;

    // --- для тестов и отладки ---
    void placeBug(Point p) { bugs_.push_back({p, 0.f}); }
    void placeBit(Point p, char value) { bits_.push_back({p, value}); }
    void clearBugs() { bugs_.clear(); }
    void clearBits() { bits_.clear(); }

private:
    [[nodiscard]] bool inside(Point p) const;
    [[nodiscard]] bool walkable(Point p) const;
    [[nodiscard]] int bugAt(Point p) const;
    [[nodiscard]] int index(Point p) const { return p.y * width_ + p.x; }
    [[nodiscard]] int bugCap() const;
    [[nodiscard]] float bugStepInterval() const;

    void enterCell(Point p);
    void collectBitAt(Point p);
    void hurt();
    void rebuildDistanceField();
    void updateBits(float dt);
    void updateBugs(float dt);
    void stepBug(std::size_t i);
    void updateWipe(float dt);
    void sweepColumn(int column);
    bool spawnBug();
    bool spawnBit();
    template <typename Pred>
    bool randomCell(Pred&& pred, Point& out);
    void emit(EventType type, Point pos, int value = 0) { events_.push_back({type, pos, value}); }

    Config cfg_;
    std::mt19937 rng_;

    int width_ = 0;
    int height_ = 0;
    std::vector<Cell> cells_;
    std::vector<int> distance_;

    Point hero_;
    Dir facing_ = Dir::Right;
    int brightness_ = 0;
    float invuln_ = 0.f;
    float lastMoveTime_ = 0.f;
    float moveCooldown_ = 0.f;
    float eraseCd_ = 0.f;
    float dashCd_ = 0.f;

    int totalBits_ = 0;
    int bytes_ = 0;
    std::string currentByte_;

    Point portal_;
    bool portalOpen_ = false;
    bool dead_ = false;
    bool escaped_ = false;

    std::vector<Bug> bugs_;
    std::vector<Bit> bits_;
    float bugSpawnTimer_ = 0.f;
    float bitTimer_ = 0.f;

    WipePhase wipePhase_ = WipePhase::Idle;
    float wipeTimer_ = 0.f;
    float wipeProgress_ = 0.f;
    int wipeColumn_ = -1;

    float time_ = 0.f;
    std::vector<Event> events_;
};

}  // namespace pix::terminal
