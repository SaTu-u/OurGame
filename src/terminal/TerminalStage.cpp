#include "terminal/TerminalStage.hpp"

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstdio>
#include <deque>
#include <random>
#include <string_view>
#include <thread>

#include "core/Utf8.hpp"
#include "terminal/Console.hpp"
#include "terminal/Screen.hpp"
#include "terminal/TerminalWorld.hpp"

namespace pix::terminal {

namespace {

using Clock = std::chrono::steady_clock;

constexpr int kScreenW = 80;
constexpr int kScreenH = 24;
constexpr int kMapY = 1;           // карта начинается со второй строки
constexpr int kMapW = 60;
constexpr int kMapH = 18;
constexpr int kPanelX = 61;
constexpr int kPanelW = kScreenW - kPanelX;
constexpr int kLogY = 20;
constexpr int kLogLines = 3;
constexpr auto kFrame = std::chrono::milliseconds(33);  // ~30 кадров в секунду — терминалу хватает

const Style kPlain{};
const Style kDim{Color::Gray};
const Style kAccent{Color::BrightCyan, Color::Default, true};
const Style kGood{Color::BrightGreen};
const Style kBad{Color::BrightRed, Color::Default, true};
const Style kWarn{Color::BrightYellow, Color::Default, true};
const Style kTitleBar{Color::Black, Color::White};

bool blink(float time, float period) { return static_cast<int>(time / period) % 2 == 0; }

std::string formatTime(float seconds) {
    const int total = static_cast<int>(seconds);
    char buffer[16];
    std::snprintf(buffer, sizeof(buffer), "%02d:%02d", total / 60, total % 60);
    return buffer;
}

/// «Шум» на полу: детерминированный, чтобы точки не прыгали каждый кадр.
bool floorSpeck(int x, int y) { return ((x * 73856093) ^ (y * 19349663)) % 23 == 0; }

struct LogLine {
    std::string text;
    Style style;
};

struct Effect {
    Point pos;
    char32_t ch;
    Style style;
    float ttl;
};

bool isConfirm(const KeyPress& k) { return k.key == Key::Enter || k.key == Key::Space; }

class TerminalStage {
public:
    TerminalStage(const Story& story, const std::vector<std::string>& map, Progress& progress)
        : story_(story), map_(map), progress_(progress), screen_(kScreenW, kScreenH) {}

    TerminalOutcome run() {
        if (!waitForBigEnoughWindow()) return TerminalOutcome::Quit;
        if (!boot()) return TerminalOutcome::Quit;
        if (!typeStory(story_.section("terminal_intro"), "ВСТУПЛЕНИЕ")) return TerminalOutcome::Quit;
        if (!play()) return TerminalOutcome::Quit;
        if (!typeStory(story_.section("terminal_outro"), "ПОБЕГ")) return TerminalOutcome::Quit;
        installDriver();
        return TerminalOutcome::Escaped;
    }

private:
    // ------------------------------------------------------------ вывод

    void present() {
        const ConsoleSize size = console_.size();
        if (size.cols != lastSize_.cols || size.rows != lastSize_.rows) {
            console_.write("\x1b[0m\x1b[2J");
            screen_.invalidate();
            lastSize_ = size;
        }
        const int ox = std::max(0, (size.cols - kScreenW) / 2);
        const int oy = std::max(0, (size.rows - kScreenH) / 2);
        console_.write(screen_.diff(ox, oy));
        console_.flush();
    }

    void sleepFrame() { std::this_thread::sleep_for(kFrame); }

    /// Ждём, пока игрок растянет окно терминала. false — нажал Esc.
    bool waitForBigEnoughWindow() {
        for (;;) {
            const ConsoleSize size = console_.size();
            if (size.cols >= kScreenW && size.rows >= kScreenH) return true;
            console_.write("\x1b[0m\x1b[2J\x1b[H");
            console_.write("Окно терминала слишком маленькое: " + std::to_string(size.cols) + "x" +
                           std::to_string(size.rows) + ".\r\n");
            console_.write("Растяни его хотя бы до 80x24 (или уменьши шрифт).\r\n");
            console_.write("Esc — выход.\r\n");
            console_.flush();
            for (int i = 0; i < 10; ++i) {
                while (auto key = console_.poll()) {
                    if (key->key == Key::Escape) return false;
                }
                sleepFrame();
            }
        }
    }

    /// Ждёт клавишу. Возвращает её (Escape — тоже клавиша).
    KeyPress waitKey() {
        for (;;) {
            if (auto key = console_.poll(); key && key->key != Key::None) return *key;
            sleepFrame();
        }
    }

    /// Пауза, которую можно пропустить нажатием. false — нажали Esc.
    bool pause(float seconds, bool& skip) {
        const auto until = Clock::now() + std::chrono::milliseconds(static_cast<int>(seconds * 1000));
        while (!skip && Clock::now() < until) {
            while (auto key = console_.poll()) {
                if (key->key == Key::Escape) return false;
                if (key->key != Key::None) skip = true;
            }
            sleepFrame();
        }
        return true;
    }

    // ------------------------------------------------------------ загрузка и сюжет

    bool boot() {
        struct BootLine {
            std::string_view label;
            std::string_view result;
            Style style;
        };
        const BootLine lines[] = {
            {"Проверка памяти", "640 КБ — хватит всем", kGood},
            {"Поиск видеокарты", "НЕ НАЙДЕНА", kBad},
            {"Поиск звуковой карты", "пищалка PC Speaker", kWarn},
            {"Загрузка PIXEL OS", "OK", kGood},
            {"Запуск терминала", "OK", kGood},
            {"Проверка курсора", "АНОМАЛИЯ: курсор мигает сам", kBad},
        };

        bool skip = false;
        screen_.clear();
        screen_.text(2, 1, "PIXEL BIOS v0.0.1", kAccent);
        screen_.text(2, 2, "(c) OurGame team. Все пиксели защищены.", kDim);
        present();
        if (!pause(0.6f, skip)) return false;

        int y = 4;
        for (const auto& line : lines) {
            const int x = 2 + screen_.text(2, y, line.label, kPlain);
            for (int dot = x + 1; dot < 34; ++dot) {
                screen_.put(dot, y, U'.', kDim);
                present();
                if (!pause(0.012f, skip)) return false;
            }
            screen_.text(35, y, line.result, line.style);
            present();
            if (!pause(0.25f, skip)) return false;
            ++y;
        }
        screen_.text(2, y + 1, "Нажми любую клавишу...", kDim);
        present();
        return waitKey().key != Key::Escape;
    }

    /// Печатает сюжетный текст как лог терминала. false — нажали Esc.
    bool typeStory(const std::vector<DialogueLine>& lines, std::string_view title) {
        screen_.clear();
        screen_.fill(0, 0, kScreenW, 1, U' ', kTitleBar);
        screen_.text(1, 0, "PIXEL OS", kTitleBar);
        screen_.text(kScreenW - 1 - static_cast<int>(utf8::length(title)), 0, title, kTitleBar);

        int y = 2;
        bool skip = false;
        for (const auto& line : lines) {
            const bool narrator = line.speaker.empty();
            std::string prefix = narrator ? "> " : line.speaker + ": ";
            const auto wrapped = utf8::wrap(line.text, static_cast<std::size_t>(kScreenW - 4) - utf8::length(prefix));

            if (y + static_cast<int>(wrapped.size()) > kScreenH - 3) {
                // Экран кончился — ждём Enter и начинаем с чистого листа.
                if (!waitContinue()) return false;
                screen_.fill(0, 1, kScreenW, kScreenH - 1, U' ');
                y = 2;
            }

            screen_.text(2, y, prefix, narrator ? kDim : kAccent);
            const int textX = 2 + static_cast<int>(utf8::length(prefix));
            bool lineSkip = skip;
            for (const auto& part : wrapped) {
                const auto chars = utf8::decode(part);
                for (std::size_t i = 0; i < chars.size(); ++i) {
                    screen_.put(textX + static_cast<int>(i), y, chars[i], kPlain);
                    if (!lineSkip) {
                        screen_.put(textX + static_cast<int>(i) + 1, y, U'█', kPlain);
                        present();
                        if (!pause(0.018f, lineSkip)) return false;
                        screen_.put(textX + static_cast<int>(i) + 1, y, U' ');
                    }
                }
                ++y;
            }
            present();
            if (!pause(lineSkip ? 0.05f : 0.35f, lineSkip)) return false;
            ++y;
        }
        return waitContinue();
    }

    bool waitContinue() {
        const auto start = Clock::now();
        for (;;) {
            const float t = std::chrono::duration<float>(Clock::now() - start).count();
            screen_.fill(0, kScreenH - 1, kScreenW, 1, U' ');
            if (blink(t, 0.5f)) screen_.text(2, kScreenH - 1, "[Enter] — дальше", kWarn);
            present();
            while (auto key = console_.poll()) {
                if (key->key == Key::Escape) return false;
                if (isConfirm(*key)) return true;
            }
            sleepFrame();
        }
    }

    void installDriver() {
        screen_.clear();
        screen_.text(2, 2, "> Обнаружено устройство: ВИДЕОКАРТА", kAccent);
        screen_.text(2, 4, "> Установка видеодрайвера pixel_gfx v0.1-beta", kPlain);
        present();

        std::mt19937 rng(42);
        std::uniform_int_distribution<int> stall(0, 5);
        const int barW = 50;
        for (int filled = 0; filled <= barW; ++filled) {
            screen_.put(2, 5, U'[');
            for (int i = 0; i < barW; ++i) screen_.put(3 + i, 5, i < filled ? U'█' : U'░', i < filled ? kGood : kDim);
            screen_.put(3 + barW, 5, U']');
            screen_.text(5 + barW, 5, std::to_string(filled * 100 / barW) + "%  ", kPlain);
            present();
            // Драйвер иногда «задумывается» — для достоверности.
            std::this_thread::sleep_for(std::chrono::milliseconds(stall(rng) == 0 ? 220 : 30));
        }
        screen_.text(2, 7, "> ВНИМАНИЕ: драйвер установлен КРИВО.", kBad);
        screen_.text(2, 8, "> Графика может выглядеть стрёмно. Мы предупредили.", kWarn);
        present();
        std::this_thread::sleep_for(std::chrono::milliseconds(1200));
        for (int i = 3; i >= 1; --i) {
            screen_.text(2, 10, "> Переход в графический режим через " + std::to_string(i) + "...", kAccent);
            present();
            std::this_thread::sleep_for(std::chrono::milliseconds(700));
        }
    }

    // ------------------------------------------------------------ игра

    void log(std::string text, Style style = kPlain) {
        logLines_.push_back({std::move(text), style});
        while (logLines_.size() > kLogLines) logLines_.pop_front();
    }

    void addEffect(Point p, char32_t ch, Style style, float ttl) { effects_.push_back({p, ch, style, ttl}); }

    TerminalWorld makeWorld() {
        const auto seed = static_cast<unsigned>(Clock::now().time_since_epoch().count());
        return TerminalWorld(map_, Config{}, seed);
    }

    /// Основной цикл стадии. false — игрок вышел.
    bool play() {
        TerminalWorld world = makeWorld();
        logLines_.clear();
        log("Ты — пиксель. Ходи стрелками или WASD.", kAccent);
        log("Собирай биты 0 и 1: восемь бит — это байт.", kPlain);

        bool paused = false;
        bool hintShown = false;
        auto last = Clock::now();

        for (;;) {
            const auto now = Clock::now();
            const float dt = std::min(0.1f, std::chrono::duration<float>(now - last).count());
            last = now;

            while (auto key = console_.poll()) {
                if (paused) {
                    if (key->key == Key::Escape || isConfirm(*key)) paused = false;
                    if (key->key == Key::Char && (key->ch == U'q' || key->ch == U'й')) return false;
                    continue;
                }
                if (world.dead()) {
                    if (key->key == Key::Escape) return false;
                    if (key->key == Key::Char && (key->ch == U'r' || key->ch == U'к')) {
                        world = makeWorld();
                        effects_.clear();
                        log("Перезагрузка... Пиксель снова светится.", kAccent);
                    }
                    continue;
                }
                handleKey(world, *key, paused);
            }

            if (!paused) {
                world.update(dt);
                timeInStage_ += dt;
                for (auto& e : effects_) e.ttl -= dt;
                std::erase_if(effects_, [](const Effect& e) { return e.ttl <= 0.f; });
            }
            for (const Event& event : world.drainEvents()) handleEvent(world, event);

            if (!hintShown && world.time() > 8.f && world.totalBits() == 0) {
                log("Подсказка: зелёные 0 и 1 — это биты. Просто наступи на них.", kWarn);
                hintShown = true;
            }

            if (world.escaped()) {
                progress_.bits += world.totalBits();
                progress_.playSeconds += timeInStage_;
                return true;
            }

            draw(world, paused);
            present();
            sleepFrame();
        }
    }

    void handleKey(TerminalWorld& world, const KeyPress& key, bool& paused) {
        switch (directionOf(key)) {
            case Key::Up: world.move(Dir::Up); return;
            case Key::Down: world.move(Dir::Down); return;
            case Key::Left: world.move(Dir::Left); return;
            case Key::Right: world.move(Dir::Right); return;
            default: break;
        }
        if (key.key == Key::Escape) {
            paused = true;
        } else if (key.key == Key::Space || key.key == Key::Backspace) {
            if (!world.eraseUnlocked()) log("Backspace пока не работает. Нужен хотя бы 1 байт.", kDim);
            else if (!world.erase()) log("Backspace перезаряжается...", kDim);
        } else if (key.key == Key::Tab) {
            if (!world.dashUnlocked()) log("Tab пока не работает. Нужно 2 байта.", kDim);
            else world.dash();
        }
    }

    void handleEvent(const TerminalWorld& world, const Event& e) {
        switch (e.type) {
            case EventType::BitCollected:
                addEffect(e.pos, U'+', kGood, 0.2f);
                break;
            case EventType::ByteCompleted:
                if (e.value == world.config().eraseUnlockBytes) {
                    log("+1 БАЙТ! Открыт навык Backspace: [Пробел] стирает жуков вокруг.", kGood);
                } else if (e.value == world.config().dashUnlockBytes) {
                    log("+1 БАЙТ! Открыт навык Tab: рывок на 5 клеток сквозь жуков.", kGood);
                } else {
                    log("+1 БАЙТ! Пиксель стал ярче и увереннее в себе.", kGood);
                }
                break;
            case EventType::PortalOpened:
                log("Где-то открылся портал «>» к видеодрайверу. Беги к нему!", kAccent);
                break;
            case EventType::Hurt:
                if (world.brightness() > 0) log("Ай! Жук откусил кусочек яркости.", kBad);
                break;
            case EventType::GcHitHero:
                log("Сборщик мусора принял тебя за мусор! Не стой на месте!", kBad);
                break;
            case EventType::Died:
                ++progress_.deaths;
                log("Пиксель погас...", kBad);
                break;
            case EventType::Erased:
                for (int dy = -2; dy <= 2; ++dy) {
                    for (int dx = -2; dx <= 2; ++dx) {
                        if (std::max(std::abs(dx), std::abs(dy)) == 2) {
                            addEffect({e.pos.x + dx, e.pos.y + dy}, U'·', kWarn, 0.18f);
                        }
                    }
                }
                break;
            case EventType::BugErased:
                addEffect(e.pos, U'*', kWarn, 0.3f);
                break;
            case EventType::Dashed: {
                // След рывка — назад от героя, пока не упрёмся в стену.
                const Dir back = world.facing() == Dir::Left    ? Dir::Right
                                 : world.facing() == Dir::Right ? Dir::Left
                                 : world.facing() == Dir::Up    ? Dir::Down
                                                                : Dir::Up;
                const bool horizontal = back == Dir::Left || back == Dir::Right;
                Point p = e.pos;
                for (int i = 0; i < world.config().dashLength; ++i) {
                    p = step(p, back);
                    if (world.cell(p) != Cell::Floor) break;
                    addEffect(p, horizontal ? U'─' : U'│', kAccent, 0.08f * static_cast<float>(i + 2));
                }
                break;
            }
            case EventType::GcWarning:
                log("!!! Через 3 секунды запустится СБОРЩИК МУСОРА. Он удаляет всё, что стоит.", kWarn);
                break;
            case EventType::GcStarted:
                log("Сборщик мусора пошёл. ДВИГАЙСЯ!", kWarn);
                break;
            case EventType::GcKilledBug:
                addEffect(e.pos, U'x', kWarn, 0.3f);
                if (!gcKillLogged_) {
                    log("Сборщик удалил жука. Хоть какая-то польза.", kDim);
                    gcKillLogged_ = true;
                }
                break;
            case EventType::Escaped:
                break;
        }
    }

    // ------------------------------------------------------------ отрисовка

    void draw(const TerminalWorld& world, bool paused) {
        screen_.clear();
        drawHeader(world);
        drawMap(world);
        drawPanel(world);
        for (int x = 0; x < kScreenW; ++x) screen_.put(x, kLogY - 1, U'─', kDim);
        for (std::size_t i = 0; i < logLines_.size(); ++i) {
            screen_.text(1, kLogY + static_cast<int>(i), "> " + logLines_[i].text, logLines_[i].style);
        }
        screen_.text(1, kScreenH - 1, "Стрелки/WASD — ходить  Пробел — стереть  Tab — рывок  Esc — пауза", kDim);

        if (world.dead()) {
            drawDialog({"ПИКСЕЛЬ ПОГАС", "Жуки и сборщик мусора не прощают.", "", "[R] — перезагрузка   [Esc] — выход"}, kBad);
        } else if (paused) {
            drawDialog({"ПАУЗА", "", "[Esc] — продолжить   [Q] — выйти из игры"}, kAccent);
        }
    }

    void drawHeader(const TerminalWorld& world) {
        screen_.fill(0, 0, kScreenW, 1, U' ', kTitleBar);
        screen_.text(1, 0, "PIXEL OS v0.0.1 │ ИЗ ПИКСЕЛЯ В ЛЮДИ", kTitleBar);
        const std::string clock = formatTime(world.time());
        screen_.text(kScreenW - 1 - static_cast<int>(clock.size()), 0, clock, kTitleBar);

        const float t = world.time();
        if (world.gcPhase() == GcPhase::Warning) {
            const Style st = blink(t, 0.25f) ? Style{Color::Black, Color::BrightYellow, true} : kTitleBar;
            const std::string text = " СБОРЩИК МУСОРА ЧЕРЕЗ " + std::to_string(static_cast<int>(world.gcTimer()) + 1) + " ";
            screen_.text(46, 0, text, st);
        } else if (world.gcPhase() == GcPhase::Sweeping) {
            screen_.text(46, 0, " ИДЁТ СБОРКА МУСОРА ", {Color::BrightWhite, Color::Red, true});
        }
    }

    void drawMap(const TerminalWorld& world) {
        const int ox = (kMapW - world.width()) / 2;
        const int oy = kMapY + (kMapH - world.height()) / 2;
        const float t = world.time();
        const auto toScreen = [&](Point p) { return Point{ox + p.x, oy + p.y}; };

        for (int y = 0; y < world.height(); ++y) {
            for (int x = 0; x < world.width(); ++x) {
                const Point s = toScreen({x, y});
                switch (world.cell({x, y})) {
                    case Cell::Wall: screen_.put(s.x, s.y, U'▒', kDim); break;
                    case Cell::Corrupt:
                        screen_.put(s.x, s.y, blink(t + static_cast<float>(x) * 0.13f, 0.3f) ? U'%' : U'&',
                                    {Color::BrightMagenta});
                        break;
                    case Cell::Floor:
                        if (floorSpeck(x, y)) screen_.put(s.x, s.y, U'·', {Color::Gray});
                        break;
                }
            }
        }

        for (const Bit& bit : world.bits()) {
            const Point s = toScreen(bit.pos);
            screen_.put(s.x, s.y, static_cast<char32_t>(bit.value), {Color::BrightGreen, Color::Default, true});
        }

        if (world.portalOpen()) {
            const Point s = toScreen(world.portal());
            screen_.put(s.x, s.y, U'>', blink(t, 0.3f) ? Style{Color::BrightWhite, Color::Blue, true}
                                                      : Style{Color::BrightCyan, Color::Default, true});
        }

        for (const Bug& bug : world.bugs()) {
            const Point s = toScreen(bug.pos);
            const int d = world.distanceToHero(bug.pos);
            const bool hunting = d >= 0 && d <= world.config().chaseRadius;
            // Большая «Ж» — жук тебя учуял.
            screen_.put(s.x, s.y, hunting ? U'Ж' : U'ж', {Color::BrightRed, Color::Default, true});
        }

        if (world.gcPhase() == GcPhase::Sweeping && world.gcColumn() >= 0) {
            const char32_t trail[] = {U'█', U'▓', U'▒', U'░'};
            for (int k = 0; k < 4; ++k) {
                const int x = world.gcColumn() - k;
                if (x < 0) continue;
                for (int y = 0; y < world.height(); ++y) {
                    if (world.cell({x, y}) == Cell::Wall) continue;
                    const Point s = toScreen({x, y});
                    screen_.put(s.x, s.y, trail[k], {k == 0 ? Color::BrightYellow : Color::Yellow});
                }
            }
        }

        for (const Effect& e : effects_) {
            if (world.cell(e.pos) == Cell::Wall) continue;
            const Point s = toScreen(e.pos);
            screen_.put(s.x, s.y, e.ch, e.style);
        }

        // Герой — просто светящаяся клетка. Чем меньше яркости, тем он тусклее.
        const bool visible = !world.heroInvulnerable() || blink(t, 0.08f);
        if (visible && !world.dead()) {
            const Color shade = world.brightness() >= 3 ? Color::BrightWhite
                                : world.brightness() == 2 ? Color::White
                                                          : Color::Gray;
            const Point s = toScreen(world.hero());
            screen_.put(s.x, s.y, U' ', {Color::Black, shade});
        }
    }

    void drawPanel(const TerminalWorld& world) {
        const Config& cfg = world.config();
        const Style frameStyle = kDim;
        screen_.frame(kPanelX, kMapY, kPanelW, kMapH, frameStyle);
        screen_.text(kPanelX + 2, kMapY, " ПИКСЕЛЬ ", kAccent);

        const int x = kPanelX + 2;
        int y = kMapY + 2;

        screen_.text(x, y, "Яркость", kPlain);
        for (int i = 0; i < cfg.maxBrightness; ++i) {
            const bool lit = i < world.brightness();
            screen_.put(x + 9 + i, y, lit ? U'█' : U'░', lit ? Style{Color::BrightWhite} : kDim);
        }
        y += 2;

        screen_.text(x, y++, "Текущий байт:", kPlain);
        screen_.put(x, y, U'[', kDim);
        for (int i = 0; i < 8; ++i) {
            const auto& cur = world.currentByte();
            if (i < static_cast<int>(cur.size())) {
                screen_.put(x + 1 + i, y, static_cast<char32_t>(cur[static_cast<std::size_t>(i)]), kGood);
            } else {
                screen_.put(x + 1 + i, y, U'·', kDim);
            }
        }
        screen_.put(x + 9, y, U']', kDim);
        y += 1;

        screen_.text(x, y, "Байты", kPlain);
        for (int i = 0; i < cfg.bytesToWin; ++i) {
            const bool got = i < world.bytes();
            screen_.put(x + 6 + i, y, got ? U'█' : U'░', got ? kGood : kDim);
        }
        screen_.text(x + 7 + cfg.bytesToWin, y, std::to_string(std::min(world.bytes(), cfg.bytesToWin)) + "/" +
                                                    std::to_string(cfg.bytesToWin), kPlain);
        y += 2;

        screen_.text(x, y++, "НАВЫКИ", kAccent);
        y = drawSkill(x, y, world.eraseUnlocked(), "Пробел: стереть", world.eraseCooldown(), cfg.eraseCooldown,
                      "??? 1 байт");
        y = drawSkill(x, y, world.dashUnlocked(), "Tab: рывок", world.dashCooldown(), cfg.dashCooldown,
                      "??? 2 байта");
        ++y;

        screen_.text(x, y++, "ЦЕЛЬ", kAccent);
        if (world.portalOpen()) {
            screen_.text(x, y, "Беги к порталу >", blink(world.time(), 0.4f) ? kWarn : kPlain);
        } else {
            screen_.text(x, y, "Собери " + std::to_string(cfg.bytesToWin) + " байта", kPlain);
        }
    }

    int drawSkill(int x, int y, bool unlocked, std::string_view name, float cooldown, float maxCooldown,
                  std::string_view lockedText) {
        if (!unlocked) {
            screen_.text(x, y, "[ ] ", kDim);
            screen_.text(x + 4, y, lockedText, kDim);
            return y + 1;
        }
        screen_.text(x, y, "[+] ", kGood);
        screen_.text(x + 4, y, name, cooldown > 0.f ? kDim : kPlain);
        if (cooldown > 0.f) {
            const int filled = static_cast<int>((1.f - cooldown / maxCooldown) * 10.f);
            for (int i = 0; i < 10; ++i) screen_.put(x + 4 + i, y + 1, i < filled ? U'█' : U'·', kDim);
            return y + 2;
        }
        return y + 1;
    }

    void drawDialog(const std::vector<std::string>& lines, Style accent) {
        std::size_t width = 0;
        for (const auto& l : lines) width = std::max(width, utf8::length(l));
        const int w = static_cast<int>(width) + 6;
        const int h = static_cast<int>(lines.size()) + 4;
        const int x = (kMapW - w) / 2;
        const int y = kMapY + (kMapH - h) / 2;
        screen_.fill(x, y, w, h, U' ');
        screen_.frame(x, y, w, h, accent);
        for (std::size_t i = 0; i < lines.size(); ++i) {
            const int lx = x + (w - static_cast<int>(utf8::length(lines[i]))) / 2;
            screen_.text(lx, y + 2 + static_cast<int>(i), lines[i], i == 0 ? accent : kPlain);
        }
    }

    const Story& story_;
    const std::vector<std::string>& map_;
    Progress& progress_;

    Console console_;
    Screen screen_;
    ConsoleSize lastSize_{-1, -1};

    std::deque<LogLine> logLines_;
    std::vector<Effect> effects_;
    float timeInStage_ = 0.f;
    bool gcKillLogged_ = false;
};

}  // namespace

TerminalOutcome runTerminalStage(const Story& story, const std::vector<std::string>& mapLines, Progress& progress) {
    if (!Console::isInteractive()) return TerminalOutcome::NotInteractive;
    TerminalStage stage(story, mapLines, progress);
    return stage.run();
}

}  // namespace pix::terminal
