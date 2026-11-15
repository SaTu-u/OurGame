#pragma once

// Платформенный слой терминала: «сырой» ввод с клавиатуры без Enter,
// ANSI-вывод и размер окна. Windows и Linux/macOS устроены по-разному,
// поэтому реализация разнесена по #ifdef в Console.cpp, а снаружи — один интерфейс.

#include <optional>
#include <string>
#include <string_view>

namespace pix::terminal {

class Screen;

enum class Key { None, Up, Down, Left, Right, Space, Tab, Enter, Escape, Backspace, Char };

struct KeyPress {
    Key key = Key::None;
    char32_t ch = 0;  ///< для Key::Char — сам символ (в нижнем регистре)
};

struct ConsoleSize {
    int cols = 80;
    int rows = 24;
};

/// RAII: в конструкторе включает сырой режим, альтернативный экран и прячет курсор,
/// в деструкторе всё возвращает как было — даже если игра вылетела с исключением.
class Console {
public:
    /// allowAnsi = false — не пробовать ANSI и сразу рисовать средствами Windows (--classic-console).
    explicit Console(bool allowAnsi = true);
    ~Console();
    Console(const Console&) = delete;
    Console& operator=(const Console&) = delete;

    /// Запущены ли мы в настоящем терминале (а не, например, в окне вывода IDE).
    static bool isInteractive();

    /// Неблокирующее чтение одной клавиши.
    std::optional<KeyPress> poll();
    [[nodiscard]] ConsoleSize size() const;

    void write(std::string_view text);
    void flush();

    /// Понимает ли консоль ANSI-последовательности (цвета и перемещение курсора).
    /// В Windows 10+ обычно да, но не в «старом режиме» консоли и не в старых сборках Windows.
    [[nodiscard]] bool supportsAnsi() const { return ansi_; }
    /// Очистить экран (ANSI или средствами Windows — что поддерживается).
    void clear();
    /// Нарисовать экранный буфер без ANSI — через WriteConsoleOutputW (только Windows).
    void blit(const Screen& screen, int originX, int originY);

private:
    std::string out_;
    bool ansi_ = true;
};

/// Windows: переключает вывод консоли в UTF-8, чтобы русские сообщения не превращались в кракозябры.
/// На Linux и macOS терминал и так в UTF-8 — ничего не делает.
void prepareConsoleOutput();

/// Стрелки, WASD и ЦФЫВ (если забыл переключить раскладку) → направление.
/// Возвращает Key::Up/Down/Left/Right или Key::None.
Key directionOf(const KeyPress& press);

}  // namespace pix::terminal
