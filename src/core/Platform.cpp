#include "core/Platform.hpp"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <system_error>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <shellapi.h>
#endif

namespace pix::platform {

namespace fs = std::filesystem;

fs::path executableDir() {
    std::error_code ec;
#ifdef _WIN32
    // Широкая (UTF-16) версия функции: русские буквы в пути не теряются.
    std::wstring buffer(MAX_PATH, L'\0');
    for (;;) {
        const DWORD n = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
        if (n == 0) return {};
        if (n < buffer.size()) {
            buffer.resize(n);
            break;
        }
        buffer.resize(buffer.size() * 2);
    }
    return fs::path(buffer).parent_path();
#elif defined(__linux__)
    const fs::path exe = fs::read_symlink("/proc/self/exe", ec);
    return ec ? fs::path() : exe.parent_path();
#else
    return {};
#endif
}

bool enterGameDirectory() {
    std::error_code ec;
    const auto hasAssets = [&ec](const fs::path& dir) { return fs::exists(dir / "assets" / "story.txt", ec); };

    // Сначала папка с exe: игра должна брать свои файлы, а не те, что случайно лежат в текущей папке.
    const fs::path exeDir = executableDir();
    if (!exeDir.empty() && hasAssets(exeDir)) {
        fs::current_path(exeDir, ec);
        if (!ec) return true;
    }
    return hasAssets(fs::current_path(ec));
}

bool openWithDefaultApp(const fs::path& file) {
    std::error_code ec;
    const fs::path full = fs::absolute(file, ec);
    if (ec) return false;
#ifdef _WIN32
    const auto result = reinterpret_cast<INT_PTR>(
        ShellExecuteW(nullptr, L"open", full.wstring().c_str(), nullptr, nullptr, SW_SHOWNORMAL));
    return result > 32;  // так ShellExecute сообщает об успехе
#else
    std::string quoted = "'";
    for (char c : full.string()) {
        if (c == '\'') quoted += "'\\''";
        else quoted += c;
    }
    quoted += "'";
#ifdef __APPLE__
    const std::string command = "open " + quoted + " &";
#else
    const std::string command = "xdg-open " + quoted + " >/dev/null 2>&1 &";
#endif
    return std::system(command.c_str()) == 0;
#endif
}

void writeConsole(std::string_view utf8, bool toStderr) {
#ifdef _WIN32
    HANDLE handle = GetStdHandle(toStderr ? STD_ERROR_HANDLE : STD_OUTPUT_HANDLE);
    DWORD mode = 0;
    if (handle != INVALID_HANDLE_VALUE && GetConsoleMode(handle, &mode) && !utf8.empty()) {
        const int n = MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), nullptr, 0);
        std::wstring wide(static_cast<std::size_t>(n), L'\0');
        MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), wide.data(), n);
        DWORD written = 0;
        WriteConsoleW(handle, wide.data(), static_cast<DWORD>(wide.size()), &written, nullptr);
        return;
    }
#endif
    // Не консоль (вывод перенаправлен в файл) или не Windows — пишем байты как есть.
    std::FILE* out = toStderr ? stderr : stdout;
    std::fwrite(utf8.data(), 1, utf8.size(), out);
    std::fflush(out);
}

bool consoleClosesOnExit() {
#ifdef _WIN32
    // Если в консоли, кроме нас, никого нет, — её создала Windows при двойном щелчке.
    DWORD processes[2];
    return GetConsoleProcessList(processes, 2) == 1;
#else
    return false;
#endif
}

void reportFatalError(std::string_view message) {
    writeConsole("\nИгра остановилась из-за ошибки:\n  " + std::string(message) + "\n", true);

    // Копия в файл — её можно прислать разработчикам, даже если окно уже закрыто.
    std::error_code ec;
    fs::path logDir = executableDir();
    if (logDir.empty()) logDir = fs::current_path(ec);
    if (std::ofstream log(logDir / "error_log.txt", std::ios::app); log) {
        log << message << '\n';
        writeConsole("Текст ошибки сохранён в error_log.txt рядом с игрой.\n", true);
    }

    if (consoleClosesOnExit()) {
        writeConsole("\nНажми Enter, чтобы закрыть окно.\n", true);
        std::getchar();
    }
}

}  // namespace pix::platform
