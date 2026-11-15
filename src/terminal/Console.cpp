#include "terminal/Console.hpp"

#include <algorithm>
#include <cstdio>
#include <vector>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <io.h>
#include <windows.h>
#else
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>
#endif

#include "core/Platform.hpp"
#include "core/Utf8.hpp"
#include "terminal/Screen.hpp"

namespace pix::terminal {

namespace {

// Альтернативный экран (как у vim): после выхода терминал выглядит так же, как до запуска.
constexpr std::string_view kEnter = "\x1b[?1049h\x1b[?25l\x1b[2J\x1b[H";
constexpr std::string_view kLeave = "\x1b[0m\x1b[2J\x1b[?25h\x1b[?1049l";

char32_t toLower(char32_t c) {
    if (c >= U'A' && c <= U'Z') return c - U'A' + U'a';
    if (c >= U'А' && c <= U'Я') return c - U'А' + U'а';
    if (c == U'Ё') return U'ё';
    return c;
}

KeyPress fromChar(char32_t c) {
    switch (c) {
        case 3: return {Key::Escape, 0};  // Ctrl+C — тоже выход, а не убийство процесса
        case 8:
        case 127: return {Key::Backspace, 0};
        case 9: return {Key::Tab, 0};
        case 10:
        case 13: return {Key::Enter, 0};
        case 27: return {Key::Escape, 0};
        case 32: return {Key::Space, 0};
        default: return {Key::Char, toLower(c)};
    }
}

#ifdef _WIN32

DWORD gOldInMode = 0;
DWORD gOldOutMode = 0;
UINT gOldOutCp = 0;
UINT gOldInCp = 0;

#else

termios gOldTermios{};
bool gTermiosSaved = false;

/// Читает все доступные байты из stdin (без ожидания).
std::string readAvailable() {
    std::string bytes;
    char buffer[64];
    for (;;) {
        const auto n = ::read(STDIN_FILENO, buffer, sizeof(buffer));
        if (n <= 0) break;
        bytes.append(buffer, static_cast<std::size_t>(n));
        if (static_cast<std::size_t>(n) < sizeof(buffer)) break;
    }
    return bytes;
}

std::string gPending;  // байты, которые прочитали, но ещё не разобрали

#endif

}  // namespace

void prepareConsoleOutput() {
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
#endif
}

Key directionOf(const KeyPress& press) {
    switch (press.key) {
        case Key::Up:
        case Key::Down:
        case Key::Left:
        case Key::Right: return press.key;
        case Key::Char: break;
        default: return Key::None;
    }
    switch (press.ch) {
        case U'w':
        case U'ц': return Key::Up;
        case U's':
        case U'ы': return Key::Down;
        case U'a':
        case U'ф': return Key::Left;
        case U'd':
        case U'в': return Key::Right;
        default: return Key::None;
    }
}

#ifdef _WIN32

bool Console::isInteractive() {
    DWORD mode = 0;
    return GetConsoleMode(GetStdHandle(STD_INPUT_HANDLE), &mode) != 0 &&
           GetConsoleMode(GetStdHandle(STD_OUTPUT_HANDLE), &mode) != 0;
}

Console::Console(bool allowAnsi) {
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    GetConsoleMode(in, &gOldInMode);
    GetConsoleMode(out, &gOldOutMode);
    gOldOutCp = GetConsoleOutputCP();
    gOldInCp = GetConsoleCP();

    // UTF-8 — чтобы русский текст и псевдографика не превращались в кракозябры.
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
    // ANSI-последовательности (цвета, перемещение курсора) — есть в Windows 10+.
    // Если консоль их не умеет (старая Windows или «старый режим» консоли), рисуем через WinAPI.
    DWORD mode = 0;
    ansi_ = allowAnsi &&
            SetConsoleMode(out, gOldOutMode | ENABLE_VIRTUAL_TERMINAL_PROCESSING | ENABLE_PROCESSED_OUTPUT) != 0 &&
            GetConsoleMode(out, &mode) != 0 && (mode & ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0;
    // Ctrl+C приходит как обычная клавиша, а не убивает игру с неубранным терминалом.
    SetConsoleMode(in, gOldInMode & ~static_cast<DWORD>(ENABLE_PROCESSED_INPUT | ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT));

    if (ansi_) {
        write(kEnter);
        flush();
    } else {
        CONSOLE_CURSOR_INFO cursor{1, FALSE};  // прячем мигающий курсор: «курсор» здесь — герой
        SetConsoleCursorInfo(out, &cursor);
        clear();
    }
}

Console::~Console() {
    if (ansi_) {
        write(kLeave);
        flush();
    } else {
        clear();
        CONSOLE_CURSOR_INFO cursor{25, TRUE};
        SetConsoleCursorInfo(GetStdHandle(STD_OUTPUT_HANDLE), &cursor);
    }
    SetConsoleMode(GetStdHandle(STD_INPUT_HANDLE), gOldInMode);
    SetConsoleMode(GetStdHandle(STD_OUTPUT_HANDLE), gOldOutMode);
    SetConsoleOutputCP(gOldOutCp);
    SetConsoleCP(gOldInCp);
}

std::optional<KeyPress> Console::poll() {
    // Читаем события клавиатуры напрямую: так стрелки и русские буквы
    // приходят одинаково в любой консоли Windows (cmd, PowerShell, Windows Terminal).
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    for (;;) {
        DWORD pending = 0;
        if (!GetNumberOfConsoleInputEvents(in, &pending) || pending == 0) return std::nullopt;
        INPUT_RECORD record{};
        DWORD read = 0;
        if (!ReadConsoleInputW(in, &record, 1, &read) || read == 0) return std::nullopt;
        if (record.EventType != KEY_EVENT || !record.Event.KeyEvent.bKeyDown) continue;

        const KEY_EVENT_RECORD& key = record.Event.KeyEvent;
        switch (key.wVirtualKeyCode) {
            case VK_UP: return KeyPress{Key::Up, 0};
            case VK_DOWN: return KeyPress{Key::Down, 0};
            case VK_LEFT: return KeyPress{Key::Left, 0};
            case VK_RIGHT: return KeyPress{Key::Right, 0};
            case VK_ESCAPE: return KeyPress{Key::Escape, 0};
            case VK_RETURN: return KeyPress{Key::Enter, 0};
            case VK_TAB: return KeyPress{Key::Tab, 0};
            case VK_BACK: return KeyPress{Key::Backspace, 0};
            case VK_SPACE: return KeyPress{Key::Space, 0};
            default: break;
        }
        const wchar_t ch = key.uChar.UnicodeChar;
        if (ch == 0) continue;  // Shift, Ctrl, F1 и прочие клавиши без символа
        return fromChar(static_cast<char32_t>(ch));
    }
}

ConsoleSize Console::size() const {
    CONSOLE_SCREEN_BUFFER_INFO info{};
    if (!GetConsoleScreenBufferInfo(GetStdHandle(STD_OUTPUT_HANDLE), &info)) return {};
    return {info.srWindow.Right - info.srWindow.Left + 1, info.srWindow.Bottom - info.srWindow.Top + 1};
}

void Console::clear() {
    if (ansi_) {
        write("\x1b[0m\x1b[2J\x1b[H");
        flush();
        return;
    }
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    CONSOLE_SCREEN_BUFFER_INFO info{};
    if (!GetConsoleScreenBufferInfo(out, &info)) return;
    const DWORD cells = static_cast<DWORD>(info.dwSize.X) * static_cast<DWORD>(info.dwSize.Y);
    DWORD written = 0;
    FillConsoleOutputCharacterW(out, L' ', cells, {0, 0}, &written);
    FillConsoleOutputAttribute(out, FOREGROUND_RED | FOREGROUND_GREEN | FOREGROUND_BLUE, cells, {0, 0}, &written);
    SetConsoleCursorPosition(out, info.srWindow.Left == 0 && info.srWindow.Top == 0 ? COORD{0, 0}
                                                                                     : COORD{info.srWindow.Left, info.srWindow.Top});
}

namespace {

/// Цвет из нашей палитры → атрибут консоли Windows (4 бита: синий, зелёный, красный, яркость).
WORD consoleColor(Color c, bool background) {
    WORD value = 0;
    switch (c) {
        case Color::Default: value = background ? 0 : 7; break;
        case Color::Black: value = 0; break;
        case Color::Red: value = 4; break;
        case Color::Green: value = 2; break;
        case Color::Yellow: value = 6; break;
        case Color::Blue: value = 1; break;
        case Color::Magenta: value = 5; break;
        case Color::Cyan: value = 3; break;
        case Color::White: value = 7; break;
        case Color::Gray: value = 8; break;
        case Color::BrightRed: value = 12; break;
        case Color::BrightGreen: value = 10; break;
        case Color::BrightYellow: value = 14; break;
        case Color::BrightBlue: value = 9; break;
        case Color::BrightMagenta: value = 13; break;
        case Color::BrightCyan: value = 11; break;
        case Color::BrightWhite: value = 15; break;
    }
    return background ? static_cast<WORD>(value << 4) : value;
}

}  // namespace

void Console::blit(const Screen& screen, int originX, int originY) {
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    CONSOLE_SCREEN_BUFFER_INFO info{};
    if (!GetConsoleScreenBufferInfo(out, &info)) return;

    const int w = screen.width();
    const int h = screen.height();
    std::vector<CHAR_INFO> cells(static_cast<std::size_t>(w * h));
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const Glyph& g = screen.at(x, y);
            CHAR_INFO& c = cells[static_cast<std::size_t>(y * w + x)];
            c.Char.UnicodeChar = g.ch <= 0xFFFF ? static_cast<WCHAR>(g.ch) : L'?';
            WORD fg = consoleColor(g.style.fg, false);
            if (g.style.bold && fg < 8) fg |= FOREGROUND_INTENSITY;
            c.Attributes = static_cast<WORD>(fg | consoleColor(g.style.bg, true));
        }
    }
    // Координаты — в буфере консоли, а видимое окно может быть прокручено.
    const auto left = static_cast<SHORT>(info.srWindow.Left + originX);
    const auto top = static_cast<SHORT>(info.srWindow.Top + originY);
    SMALL_RECT region{left, top, static_cast<SHORT>(left + w - 1), static_cast<SHORT>(top + h - 1)};
    WriteConsoleOutputW(out, cells.data(), {static_cast<SHORT>(w), static_cast<SHORT>(h)}, {0, 0}, &region);
}

#else  // Linux / macOS

bool Console::isInteractive() { return ::isatty(STDIN_FILENO) && ::isatty(STDOUT_FILENO); }

Console::Console(bool /*allowAnsi*/) {
    if (::tcgetattr(STDIN_FILENO, &gOldTermios) == 0) {
        gTermiosSaved = true;
        termios raw = gOldTermios;
        // Без эха, без ожидания Enter, без обработки Ctrl+C терминалом.
        raw.c_lflag &= ~static_cast<tcflag_t>(ICANON | ECHO | ISIG | IEXTEN);
        raw.c_iflag &= ~static_cast<tcflag_t>(IXON | ICRNL);
        raw.c_cc[VMIN] = 0;
        raw.c_cc[VTIME] = 0;
        ::tcsetattr(STDIN_FILENO, TCSAFLUSH, &raw);
    }
    write(kEnter);
    flush();
}

Console::~Console() {
    write(kLeave);
    flush();
    if (gTermiosSaved) ::tcsetattr(STDIN_FILENO, TCSAFLUSH, &gOldTermios);
}

std::optional<KeyPress> Console::poll() {
    gPending += readAvailable();
    if (gPending.empty()) return std::nullopt;

    const auto consume = [](std::size_t n, KeyPress key) {
        gPending.erase(0, n);
        return std::optional<KeyPress>(key);
    };

    if (gPending[0] == '\x1b') {
        // Стрелки: ESC [ A  или  ESC O A (зависит от терминала).
        if (gPending.size() >= 3 && (gPending[1] == '[' || gPending[1] == 'O')) {
            switch (gPending[2]) {
                case 'A': return consume(3, {Key::Up, 0});
                case 'B': return consume(3, {Key::Down, 0});
                case 'C': return consume(3, {Key::Right, 0});
                case 'D': return consume(3, {Key::Left, 0});
                default: break;
            }
            // Незнакомая последовательность — выбрасываем её целиком до буквы-терминатора.
            std::size_t end = 2;
            while (end < gPending.size() && !(gPending[end] >= '@' && gPending[end] <= '~')) ++end;
            return consume(std::min(end + 1, gPending.size()), {Key::None, 0});
        }
        return consume(1, {Key::Escape, 0});
    }

    // Один символ UTF-8 (русские буквы занимают 2 байта).
    const auto lead = static_cast<unsigned char>(gPending[0]);
    std::size_t len = 1;
    if ((lead & 0xE0u) == 0xC0u) len = 2;
    else if ((lead & 0xF0u) == 0xE0u) len = 3;
    else if ((lead & 0xF8u) == 0xF0u) len = 4;
    if (gPending.size() < len) return std::nullopt;  // остаток символа придёт позже

    const auto decoded = utf8::decode(std::string_view(gPending).substr(0, len));
    return consume(len, fromChar(decoded.empty() ? U'\0' : decoded[0]));
}

void Console::clear() {
    write("\x1b[0m\x1b[2J\x1b[H");
    flush();
}

void Console::blit(const Screen&, int, int) {}  // в Linux и macOS терминал всегда понимает ANSI

ConsoleSize Console::size() const {
    winsize ws{};
    if (::ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) != 0 || ws.ws_col == 0) return {};
    return {ws.ws_col, ws.ws_row};
}

#endif

void Console::write(std::string_view text) { out_.append(text); }

void Console::flush() {
    if (out_.empty()) return;
    platform::writeConsole(out_);
    out_.clear();
}

}  // namespace pix::terminal
