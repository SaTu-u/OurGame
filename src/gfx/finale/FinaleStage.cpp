#include "gfx/finale/FinaleStage.hpp"

#include <cmath>
#include <cstdio>
#include <optional>
#include <system_error>

#include "core/Credits.hpp"
#include "core/TextFile.hpp"

namespace pix::gfx {

namespace {

namespace fs = std::filesystem;

constexpr int kW = 1280;
constexpr int kH = 720;
constexpr Color kText{238, 240, 255, 255};
constexpr Color kDim{150, 150, 185, 255};
constexpr Color kAccent{61, 242, 255, 255};

std::optional<fs::path> findVideo(const fs::path& assets) {
    std::error_code ec;
    for (const char* ext : {".mp4", ".mov", ".mkv", ".webm", ".avi"}) {
        const fs::path candidate = assets / "video" / (std::string("finale") + ext);
        if (fs::exists(candidate, ec)) return fs::absolute(candidate, ec).make_preferred();
    }
    return std::nullopt;
}

std::string formatTime(double seconds) {
    const int total = static_cast<int>(seconds);
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    return buffer;
}

class Finale {
public:
    Finale(GameContext& ctx, const Progress& progress)
        : ctx_(ctx), progress_(progress), screen_(kW, kH, false), lines_(ctx.story->section("finale")) {
        if (auto text = readTextFile(ctx.assets / "credits.txt")) credits_ = parseCredits(*text);
        if (!ctx.noVideo) video_ = findVideo(ctx.assets);
    }

    FinaleResult run() {
        enum class Phase { Resolution, WhiteOut, Video, Credits };
        Phase phase = Phase::Resolution;
        float phaseTime = 0.f;
        std::size_t lineIndex = 0;
        float lineTime = 0.f;
        bool videoOpened = false;
        float creditsScroll = 0.f;
        float creditsHeight = 1e9f;  // узнаем после первого кадра титров

        while (!WindowShouldClose()) {
            const float dt = std::min(GetFrameTime(), 0.05f);
            time_ += dt;
            phaseTime += dt;

            screen_.begin();
            switch (phase) {
                case Phase::Resolution: {
                    lineTime += dt;
                    if ((lineTime > 2.6f || confirmPressed()) && lineIndex < lines_.size()) {
                        ++lineIndex;
                        lineTime = 0.f;
                    }
                    drawResolution(phaseTime);
                    if (lineIndex < lines_.size()) drawNarration(lines_[lineIndex].text, lineTime);
                    if (lineIndex >= lines_.size()) {
                        phase = Phase::WhiteOut;
                        phaseTime = 0.f;
                    }
                    break;
                }
                case Phase::WhiteOut: {
                    drawResolution(6.f + phaseTime);
                    DrawRectangle(0, 0, kW, kH, withAlpha(WHITE, phaseTime / 1.2f));
                    if (phaseTime > 1.f) {
                        drawTextCentered(ctx_.fonts->title, "ВЫХОД В РЕАЛЬНОСТЬ", {kW * 0.5f, kH * 0.42f}, 76.f, 3.f,
                                         withAlpha({20, 20, 30, 255}, (phaseTime - 1.f) / 0.8f));
                    }
                    if (phaseTime > 3.2f) {
                        phase = Phase::Video;
                        phaseTime = 0.f;
                    }
                    break;
                }
                case Phase::Video: {
                    if (!videoOpened && video_) {
                        // Игра «вырывается» из своего окна: видео открывается в обычном плеере системы.
                        OpenURL(video_->string().c_str());
                        videoOpened = true;
                    }
                    drawVideoScreen();
                    if (phaseTime > 0.5f && confirmPressed()) {
                        phase = Phase::Credits;
                        phaseTime = 0.f;
                    }
                    break;
                }
                case Phase::Credits: {
                    // Титры едут вверх и останавливаются, когда последняя строка чуть ниже центра.
                    const bool fast = IsKeyDown(KEY_SPACE) || IsKeyDown(KEY_ENTER) || IsKeyDown(KEY_DOWN);
                    const float maxScroll = creditsHeight + kH * 0.45f;
                    creditsScroll = std::min(creditsScroll + dt * (fast ? 360.f : 70.f), maxScroll);
                    creditsHeight = drawCredits(creditsScroll);
                    const bool atEnd = creditsScroll >= maxScroll - 1.f;
                    if ((atEnd && IsKeyPressed(KEY_ENTER)) || IsKeyPressed(KEY_ESCAPE)) {
                        screen_.end();
                        return FinaleResult::Finished;
                    }
                    break;
                }
            }
            screen_.end();

            BeginDrawing();
            ClearBackground(BLACK);
            screen_.present();
            EndDrawing();
        }
        return FinaleResult::Quit;
    }

private:
    /// Сетка «пикселей», которые становятся всё мельче, пока не превратятся в гладкую картинку.
    void drawResolution(float t) const {
        ClearBackground({6, 4, 18, 255});
        const float cell = std::max(3.f, 128.f * std::pow(0.62f, t));
        const int cols = static_cast<int>(kW / cell) + 1;
        const int rows = static_cast<int>(kH / cell) + 1;
        const float fadeOut = std::clamp((t - 4.5f) / 2.f, 0.f, 1.f);
        for (int y = 0; y < rows; ++y) {
            for (int x = 0; x < cols; ++x) {
                const float fx = static_cast<float>(x) * cell;
                const float fy = static_cast<float>(y) * cell;
                const float wave = std::sin(fx * 0.004f + time_) * std::cos(fy * 0.005f - time_ * 0.7f);
                const float k = static_cast<float>(y) / static_cast<float>(rows);
                Color c = mix({60, 20, 110, 255}, {255, 90, 140, 255}, k);
                c = mix(c, {61, 242, 255, 255}, std::max(0.f, wave) * 0.6f);
                c = mix(c, {6, 4, 18, 255}, fadeOut * 0.85f);
                DrawRectangle(static_cast<int>(fx), static_cast<int>(fy), static_cast<int>(std::ceil(cell)) - 1,
                              static_cast<int>(std::ceil(cell)) - 1, c);
            }
        }
    }

    void drawNarration(const std::string& text, float t) const {
        const float alpha = std::min(1.f, t * 2.f);
        const auto rows = wrapText(ctx_.fonts->ui, text, 36.f, 1.f, kW - 300.f);
        const float h = static_cast<float>(rows.size()) * 44.f;
        DrawRectangleRounded({120.f, kH * 0.5f - h * 0.5f - 24.f, kW - 240.f, h + 48.f}, 0.3f, 10,
                             withAlpha({6, 4, 18, 255}, 0.75f * alpha));
        float y = kH * 0.5f - h * 0.5f;
        for (const auto& row : rows) {
            drawTextCentered(ctx_.fonts->ui, row, {kW * 0.5f, y}, 36.f, 1.f, withAlpha(kText, alpha));
            y += 44.f;
        }
    }

    void drawVideoScreen() const {
        ClearBackground({8, 8, 14, 255});
        const float pulse = 0.5f + 0.5f * std::sin(time_ * 3.f);
        if (video_) {
            drawTextCentered(ctx_.fonts->title, "Видео открылось в плеере", {kW * 0.5f, kH * 0.3f}, 56.f, 1.f, kText);
            drawTextCentered(ctx_.fonts->ui, "Пиксель, Глюк и Шейдер вышли в реальность. Посмотри, что было дальше.",
                             {kW * 0.5f, kH * 0.45f}, 28.f, 1.f, kDim);
        } else {
            drawTextCentered(ctx_.fonts->title, "[ здесь будет видео из жизни ]", {kW * 0.5f, kH * 0.3f}, 52.f, 1.f,
                             kText);
            drawTextCentered(ctx_.fonts->ui, "Положите ролик в assets/video/finale.mp4 — игра откроет его сама.",
                             {kW * 0.5f, kH * 0.45f}, 26.f, 1.f, kDim);
        }
        drawTextCentered(ctx_.fonts->ui, "Enter — к титрам", {kW * 0.5f, kH * 0.72f}, 26.f, 1.f,
                         withAlpha(kAccent, 0.4f + 0.6f * pulse));
    }

    /// Рисует титры со сдвигом scroll. Возвращает их полную высоту (без учёта последней строки).
    float drawCredits(float scroll) const {
        ClearBackground({6, 4, 18, 255});
        DrawRectangleGradientV(0, kH / 2, kW, kH / 2, {6, 4, 18, 255}, {40, 14, 60, 255});

        float y = kH - scroll;
        const auto line = [&](const std::string& text, const Font& font, float size, Color color, float gap) {
            if (y > -80.f && y < kH + 20.f) drawTextCentered(font, text, {kW * 0.5f, y}, size, 1.f, color);
            y += size + gap;
        };

        line("ИЗ ПИКСЕЛЯ В ЛЮДИ", ctx_.fonts->title, 84.f, kText, 10.f);
        line("инди-игра, которая выросла вместе с героем", ctx_.fonts->ui, 28.f, kDim, 90.f);

        for (const CreditSection& section : credits_) {
            if (!section.title.empty()) line(section.title, ctx_.fonts->ui, 26.f, kAccent, 18.f);
            for (const CreditEntry& e : section.entries) {
                const std::string text = e.role.empty() ? e.name : (e.name.empty() ? e.role : e.role + " — " + e.name);
                line(text, ctx_.fonts->ui, 32.f, kText, 14.f);
            }
            y += 60.f;
        }

        // Статистика — моноширинным шрифтом, как распечатка из того самого терминала.
        line("Твоё прохождение", ctx_.fonts->ui, 26.f, kAccent, 18.f);
        line("> бит собрано ....... " + std::to_string(progress_.bits), ctx_.fonts->mono, 28.f, kText, 12.f);
        line("> пикселей собрано .. " + std::to_string(progress_.pixels), ctx_.fonts->mono, 28.f, kText, 12.f);
        line("> смертей ........... " + std::to_string(progress_.deaths), ctx_.fonts->mono, 28.f, kText, 12.f);
        line("> время в игре ...... " + formatTime(progress_.playSeconds), ctx_.fonts->mono, 28.f, kText, 80.f);

        for (const auto& thanks : ctx_.story->section("credits_thanks")) {
            line(thanks.text, ctx_.fonts->ui, 30.f, kText, 14.f);
        }
        y += 80.f;
        const float height = y - (kH - scroll);
        line("Enter — выйти", ctx_.fonts->ui, 26.f, withAlpha(kAccent, 0.5f + 0.5f * std::sin(time_ * 3.f)), 0.f);

        // Текст плавно появляется снизу и тает наверху.
        DrawRectangleGradientV(0, 0, kW, 150, {6, 4, 18, 255}, {6, 4, 18, 0});
        DrawRectangleGradientV(0, kH - 190, kW, 190, {40, 14, 60, 0}, {40, 14, 60, 255});

        // Три «пикселя» — герой и кенты — летают внизу экрана.
        const Color heroes[] = {{245, 247, 255, 255}, {110, 255, 150, 255}, {255, 190, 90, 255}};
        for (int i = 0; i < 3; ++i) {
            const float x = kW * 0.5f + (static_cast<float>(i) - 1.f) * 90.f;
            const float hy = kH - 70.f + std::sin(time_ * 2.f + static_cast<float>(i)) * 10.f;
            DrawRectangleRounded({x - 18.f, hy - 18.f, 36.f, 36.f}, 0.35f, 6, heroes[i]);
        }
        return height;
    }

    GameContext& ctx_;
    const Progress& progress_;
    VirtualScreen screen_;
    std::vector<DialogueLine> lines_;
    std::vector<CreditSection> credits_;
    std::optional<fs::path> video_;
    float time_ = 0.f;
};

}  // namespace

FinaleResult runFinale(GameContext& ctx, const Progress& progress) {
    Finale finale(ctx, progress);
    return finale.run();
}

}  // namespace pix::gfx
