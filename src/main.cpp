// ИЗ ПИКСЕЛЯ В ЛЮДИ
// Точка входа: решает, с какой стадии начать, и по очереди запускает стадии.
//
//   1. Терминал   — игра в консоли, ты пиксель           (src/terminal)
//   2. Стрёмное 2D — окно 960x540, графика из Paint       (src/gfx/crude)
//   3. Красивое 2D — окно растёт до 1280x720, неон и свет (src/gfx/pretty)
//   4. Финал      — выход в реальность и видео            (src/gfx/finale)

#include <raylib.h>

#include <cstdio>
#include <exception>
#include <string>
#include <vector>

#include "core/Options.hpp"
#include "core/Progress.hpp"
#include "core/Story.hpp"
#include "core/TextFile.hpp"
#include "gfx/Gfx.hpp"
#include "gfx/PlatformerStage.hpp"
#include "gfx/crude/CrudeRenderer.hpp"
#include "gfx/pretty/PrettyRenderer.hpp"
#include "terminal/Console.hpp"
#include "terminal/TerminalStage.hpp"

namespace {

using namespace pix;
namespace fs = std::filesystem;

struct WindowSize {
    int w;
    int h;
};
constexpr WindowSize kCrudeWindow{960, 540};
constexpr WindowSize kPrettyWindow{1280, 720};

/// Прогресс для старта с конкретной стадии (--stage N): выдаём то, что игрок получил бы раньше.
Progress progressForStage(StageId stage) {
    Progress p;
    p.stage = stage;
    p.doubleJump = stage >= StageId::Pretty;
    p.dash = stage >= StageId::Finale;
    return p;
}

void centerWindow(WindowSize size) {
    const int monitor = GetCurrentMonitor();
    SetWindowPosition((GetMonitorWidth(monitor) - size.w) / 2, (GetMonitorHeight(monitor) - size.h) / 2);
}

/// Окно плавно вырастает — игра буквально становится больше вместе с героем.
void growWindow(WindowSize from, WindowSize to) {
    constexpr int kFrames = 45;
    for (int i = 1; i <= kFrames && !WindowShouldClose(); ++i) {
        const float t = gfx::easeOutCubic(static_cast<float>(i) / kFrames);
        const WindowSize now{static_cast<int>(gfx::lerp(static_cast<float>(from.w), static_cast<float>(to.w), t)),
                             static_cast<int>(gfx::lerp(static_cast<float>(from.h), static_cast<float>(to.h), t))};
        SetWindowSize(now.w, now.h);
        centerWindow(now);
        BeginDrawing();
        ClearBackground(BLACK);
        EndDrawing();
    }
}

class Game {
public:
    Game(const Options& options, const char* exePath)
        : options_(options),
          assets_(findAssetsDir(exePath ? exePath : "")),
          savePath_(assets_.parent_path() / "save.txt"),
          story_(Story::loadFile(assets_ / "story.txt")) {
        if (options_.startStage) {
            progress_ = progressForStage(*options_.startStage);
        } else if (auto saved = options_.fresh ? std::nullopt : loadProgress(savePath_)) {
            progress_ = *saved;
            if (progress_.stage != StageId::Terminal) {
                std::printf("Продолжаем с сохранения: стадия %d. Начать заново: --new\n",
                            static_cast<int>(progress_.stage));
            }
        }
    }

    int run() {
        if (progress_.stage == StageId::Terminal) {
            if (!runTerminal()) return 0;
        }
        runGraphics();
        return 0;
    }

private:
    void save() {
        if (!saveProgress(progress_, savePath_)) {
            std::fprintf(stderr, "Не удалось сохранить прогресс в %s\n", savePath_.string().c_str());
        }
    }

    void advanceTo(StageId stage) {
        progress_.stage = stage;
        progress_.level = 0;
        save();
    }

    /// false — игрок вышел из игры.
    bool runTerminal() {
        const auto text = readTextFile(assets_ / "levels" / "terminal.txt");
        if (!text) throw std::runtime_error("не найдена карта assets/levels/terminal.txt");

        switch (terminal::runTerminalStage(story_, splitLines(*text), progress_)) {
            case terminal::TerminalOutcome::Quit:
                save();
                return false;
            case terminal::TerminalOutcome::NotInteractive:
                std::printf("Стадия 1 работает только в настоящем терминале.\n"
                            "Запусти игру из консоли (cmd, PowerShell, Terminal), чтобы сыграть за пиксель.\n"
                            "А пока — сразу в графический режим...\n");
                break;
            case terminal::TerminalOutcome::Escaped:
                std::printf("Видеодрайвер установлен. Не закрывай эту консоль — в ней живёт душа игры.\n");
                break;
        }
        advanceTo(StageId::Crude);
        return true;
    }

    void runGraphics() {
        SetConfigFlags(FLAG_WINDOW_RESIZABLE | FLAG_VSYNC_HINT | FLAG_MSAA_4X_HINT);
        SetTraceLogLevel(LOG_WARNING);
        const WindowSize start = progress_.stage == StageId::Crude ? kCrudeWindow : kPrettyWindow;
        InitWindow(start.w, start.h, "Из пикселя в люди");
        SetExitKey(KEY_NULL);  // Esc — это пауза, а не выход
        SetTargetFPS(144);
        InitAudioDevice();

        gfx::Fonts fonts = gfx::loadFonts(assets_);
        gfx::GameContext ctx;
        ctx.assets = assets_;
        ctx.story = &story_;
        ctx.fonts = &fonts;
        ctx.save = [this] { save(); };
        ctx.noVideo = options_.noVideo;

        bool running = true;
        while (running && !WindowShouldClose()) {
            switch (progress_.stage) {
                case StageId::Terminal:  // сюда не попадаем, но компилятор любит полноту
                case StageId::Crude: running = runCrude(ctx); break;
                case StageId::Pretty: running = runPretty(ctx); break;
                case StageId::Finale:
                    running = false;  // финал — следующий шаг
                    break;
            }
        }

        gfx::unloadFonts(fonts);
        CloseAudioDevice();
        CloseWindow();
    }

    bool runCrude(gfx::GameContext& ctx) {
        SetWindowTitle("Из пикселя в люди (видеодрайвер v0.1-beta, НЕ ОТВЕЧАЕТ)");
        gfx::CrudeRenderer renderer(ctx);
        gfx::StageSetup setup;
        setup.levels = {"crude_1.txt", "crude_2.txt"};
        setup.physics = platformer::PhysicsConfig::crude();
        setup.sound = gfx::SfxStyle::Crude;
        setup.outro = "crude_outro";
        if (gfx::runPlatformerStage(ctx, setup, renderer, progress_) != gfx::StageResult::Completed) {
            save();
            return false;
        }
        advanceTo(StageId::Pretty);
        growWindow({GetScreenWidth(), GetScreenHeight()}, kPrettyWindow);
        return true;
    }

    bool runPretty(gfx::GameContext& ctx) {
        SetWindowTitle("Из пикселя в люди");
        gfx::PrettyRenderer renderer(ctx);
        gfx::StageSetup setup;
        setup.levels = {"pretty_1.txt", "pretty_2.txt"};
        setup.physics = platformer::PhysicsConfig::polished();
        setup.sound = gfx::SfxStyle::Pretty;
        if (gfx::runPlatformerStage(ctx, setup, renderer, progress_) != gfx::StageResult::Completed) {
            save();
            return false;
        }
        advanceTo(StageId::Finale);
        return true;
    }

    Options options_;
    fs::path assets_;
    fs::path savePath_;
    Story story_;
    Progress progress_;
};

}  // namespace

int main(int argc, char** argv) {
    terminal::prepareConsoleOutput();
    std::vector<std::string> args(argv + 1, argv + argc);
    const Options options = parseOptions(args);
    if (options.showHelp || !options.errors.empty()) {
        for (const auto& e : options.errors) std::fprintf(stderr, "Ошибка: %s\n", e.c_str());
        std::printf("%s", usageText().c_str());
        return options.errors.empty() ? 0 : 1;
    }

    try {
        Game game(options, argc > 0 ? argv[0] : nullptr);
        return game.run();
    } catch (const std::exception& e) {
        std::fprintf(stderr, "Игра упала: %s\n", e.what());
        return 1;
    }
}
