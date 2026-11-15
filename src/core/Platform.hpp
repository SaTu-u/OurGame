#pragma once

// То немногое, что в Windows и Linux/macOS делается по-разному и не относится к консоли.
//
// Главная беда, ради которой этот файл появился: в Windows путь к игре часто содержит
// русские буквы (C:\Users\Тимур\...). Узкие строки char в Windows — это не UTF-8, и попытка
// превратить такой путь в std::filesystem::path или передать его в raylib роняет игру.
// Поэтому при запуске мы переходим в папку с игрой и дальше пользуемся только
// относительными путями вида assets/fonts/... — в них нет ни одной русской буквы.

#include <filesystem>
#include <string_view>

namespace pix::platform {

/// Папка, где лежит exe (пустой путь, если узнать не удалось).
std::filesystem::path executableDir();

/// Сделать текущей папку с игрой: ту, где рядом лежит assets/story.txt.
/// Возвращает true, если такая папка нашлась.
bool enterGameDirectory();

/// Открыть файл программой по умолчанию (видео — в видеоплеере). Работает и с русскими путями.
bool openWithDefaultApp(const std::filesystem::path& file);

/// Напечатать UTF-8 текст в консоль. В Windows идёт через WriteConsoleW (UTF-16),
/// поэтому русские буквы видны при любой кодовой странице консоли.
void writeConsole(std::string_view utf8, bool toStderr = false);

/// Windows: консоль создана только ради нас (игру запустили двойным щелчком)
/// и закроется сразу после выхода. В Linux/macOS всегда false.
bool consoleClosesOnExit();

/// Показать сообщение об ошибке так, чтобы его успели прочитать: напечатать,
/// записать в error_log.txt и, если окно консоли иначе сразу закроется, ждать Enter.
void reportFatalError(std::string_view message);

}  // namespace pix::platform
