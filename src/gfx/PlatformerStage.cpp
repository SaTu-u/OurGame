#include "gfx/PlatformerStage.hpp"

#include <optional>
#include <stdexcept>

#include "core/TextFile.hpp"

namespace pix::gfx {

namespace {

using namespace platformer;

constexpr float kFadeTime = 0.6f;

platformer::Level loadLevel(const std::filesystem::path& assets, const std::string& file) {
    const auto path = assets / "levels" / file;
    const auto text = readTextFile(path);
    if (!text) throw std::runtime_error("не найден уровень " + path.string());
    return parseLevel(splitLines(*text));
}

/// Клавиатура + геймпад → команды для мира.
Input readInput() {
    Input in;
    in.left = IsKeyDown(KEY_LEFT) || IsKeyDown(KEY_A);
    in.right = IsKeyDown(KEY_RIGHT) || IsKeyDown(KEY_D);
    in.jumpHeld = IsKeyDown(KEY_SPACE) || IsKeyDown(KEY_UP) || IsKeyDown(KEY_W) || IsKeyDown(KEY_Z);
    in.jumpPressed = IsKeyPressed(KEY_SPACE) || IsKeyPressed(KEY_UP) || IsKeyPressed(KEY_W) || IsKeyPressed(KEY_Z);
    in.dashPressed = IsKeyPressed(KEY_LEFT_SHIFT) || IsKeyPressed(KEY_RIGHT_SHIFT) || IsKeyPressed(KEY_X);

    if (IsGamepadAvailable(0)) {
        const float axis = GetGamepadAxisMovement(0, GAMEPAD_AXIS_LEFT_X);
        in.left = in.left || axis < -0.3f || IsGamepadButtonDown(0, GAMEPAD_BUTTON_LEFT_FACE_LEFT);
        in.right = in.right || axis > 0.3f || IsGamepadButtonDown(0, GAMEPAD_BUTTON_LEFT_FACE_RIGHT);
        in.jumpHeld = in.jumpHeld || IsGamepadButtonDown(0, GAMEPAD_BUTTON_RIGHT_FACE_DOWN);
        in.jumpPressed = in.jumpPressed || IsGamepadButtonPressed(0, GAMEPAD_BUTTON_RIGHT_FACE_DOWN);
        in.dashPressed = in.dashPressed || IsGamepadButtonPressed(0, GAMEPAD_BUTTON_RIGHT_FACE_LEFT) ||
                         IsGamepadButtonPressed(0, GAMEPAD_BUTTON_RIGHT_TRIGGER_1);
    }
    return in;
}

std::optional<Sfx> soundFor(EventType type) {
    switch (type) {
        case EventType::Jump: return Sfx::Jump;
        case EventType::AirJump: return Sfx::AirJump;
        case EventType::Land: return Sfx::Land;
        case EventType::Dash: return Sfx::Dash;
        case EventType::Pickup: return Sfx::Pickup;
        case EventType::Stomp:
        case EventType::DashKill: return Sfx::Stomp;
        case EventType::Hurt: return Sfx::Hurt;
        case EventType::Death: return Sfx::Death;
        case EventType::Checkpoint: return Sfx::Checkpoint;
        case EventType::BuddyMet: return Sfx::Buddy;
        case EventType::LevelComplete: return Sfx::Complete;
        case EventType::BossShoot: return Sfx::Shoot;
        case EventType::BossSlam: return Sfx::BossSlam;
        case EventType::BossHit:
        case EventType::BossDefeated: return Sfx::BossHit;
        case EventType::BossAwake: return Sfx::Select;
        default: return std::nullopt;
    }
}

/// Один уровень: от заставки до финиша. Отдельный класс, чтобы не таскать десяток переменных.
class LevelSession {
public:
    LevelSession(GameContext& ctx, const StageSetup& setup, PlatformerRenderer& renderer, SoundBank& sounds,
                 Progress& progress, const platformer::Level& level, int index)
        : ctx_(ctx),
          setup_(setup),
          renderer_(renderer),
          sounds_(sounds),
          progress_(progress),
          level_(level),
          index_(index),
          world_(level.map, setup.physics, {progress.doubleJump, progress.dash}) {
        renderer_.beginLevel(world_, level_);
        if (!level_.intro.empty()) startDialogue(level_.intro);
    }

    /// true — уровень пройден, false — игрок вышел.
    bool run() {
        const bool lastLevel = index_ + 1 == static_cast<int>(setup_.levels.size());
        while (!WindowShouldClose()) {
            const float dt = std::min(GetFrameTime(), 0.05f);
            fadeIn_ = std::max(0.f, fadeIn_ - dt / kFadeTime);

            if (paused_) {
                if (!updatePause()) return false;
            } else if (dialogue_) {
                updateDialogue(dt);
            } else {
                if (IsKeyPressed(KEY_ESCAPE) || IsKeyPressed(KEY_P) ||
                    (IsGamepadAvailable(0) && IsGamepadButtonPressed(0, GAMEPAD_BUTTON_MIDDLE_RIGHT))) {
                    paused_ = true;
                    pauseSelection_ = 0;
                    sounds_.play(Sfx::Select);
                }
                updateWorld(dt);
                levelTime_ += dt;
                progress_.playSeconds += dt;
            }

            // Уровень пройден, все разговоры закончены — показываем финальный текст стадии и гасим экран.
            if (world_.completed() && !dialogue_) {
                if (lastLevel && !outroShown_ && !setup_.outro.empty()) {
                    outroShown_ = true;
                    startDialogue(setup_.outro);
                } else {
                    fadeOut_ += dt / kFadeTime;
                    if (fadeOut_ >= 1.f) break;
                }
            }

            renderer_.update(dt, world_);
            draw();
        }
        if (WindowShouldClose()) return false;

        progress_.pixels += world_.pickupsCollected();
        progress_.deaths += world_.deaths();
        return true;
    }

private:
    void startDialogue(std::string_view section) {
        dialogue_.emplace(ctx_.story->section(section));
        typedChars_ = 0;
    }

    void updateDialogue(float dt) {
        dialogue_->update(dt);
        // Щелчок печатной машинки — не на каждую букву, а то будет пулемёт.
        const std::size_t shown = dialogue_->visibleText().size();
        if (shown / 6 != typedChars_ / 6) sounds_.play(Sfx::Blip, 1.f, 0.4f);
        typedChars_ = shown;

        if (confirmPressed()) {
            dialogue_->advance();
            typedChars_ = dialogue_->visibleText().size();
            if (dialogue_->finished()) dialogue_.reset();
        }
    }

    bool updatePause() {
        if (IsKeyPressed(KEY_ESCAPE)) {
            paused_ = false;
            return true;
        }
        if (IsKeyPressed(KEY_UP) || IsKeyPressed(KEY_W) || IsKeyPressed(KEY_DOWN) || IsKeyPressed(KEY_S)) {
            pauseSelection_ = 1 - pauseSelection_;
            sounds_.play(Sfx::Blip);
        }
        if (confirmPressed()) {
            sounds_.play(Sfx::Select);
            if (pauseSelection_ == 1) return false;
            paused_ = false;
        }
        return true;
    }

    void updateWorld(float dt) {
        // Нажатия запоминаем до ближайшего шага физики, чтобы ни одно не потерялось.
        const Input frame = readInput();
        input_.left = frame.left;
        input_.right = frame.right;
        input_.jumpHeld = frame.jumpHeld;
        input_.jumpPressed = input_.jumpPressed || frame.jumpPressed;
        input_.dashPressed = input_.dashPressed || frame.dashPressed;

        accumulator_ += dt;
        while (accumulator_ >= kFixedStep && !dialogue_) {
            world_.update(kFixedStep, input_);
            input_.jumpPressed = false;
            input_.dashPressed = false;
            accumulator_ -= kFixedStep;
            for (const WorldEvent& e : world_.drainEvents()) handleEvent(e);
        }
        if (dialogue_) accumulator_ = 0.f;
    }

    void handleEvent(const WorldEvent& e) {
        if (auto sfx = soundFor(e.type)) sounds_.play(*sfx);
        renderer_.onEvent(e, world_);

        switch (e.type) {
            case EventType::BuddyMet:
                progress_.doubleJump = world_.abilities().doubleJump;
                progress_.dash = world_.abilities().dash;
                ctx_.save();
                startDialogue(buddyStorySection(e.value));
                break;
            case EventType::BossAwake: startDialogue("boss_intro"); break;
            case EventType::BossDefeated: startDialogue("boss_defeat"); break;
            default: break;
        }
    }

    void draw() {
        StageView view;
        view.dialogue = dialogue_ ? &*dialogue_ : nullptr;
        view.paused = paused_;
        view.pauseSelection = pauseSelection_;
        view.levelTitle = level_.title;
        view.levelIndex = index_;
        view.levelCount = static_cast<int>(setup_.levels.size());
        view.fade = std::max(fadeIn_, std::min(fadeOut_, 1.f));
        view.levelTime = levelTime_;
        view.progress = &progress_;

        BeginDrawing();
        renderer_.draw(world_, view);
        EndDrawing();
    }

    GameContext& ctx_;
    const StageSetup& setup_;
    PlatformerRenderer& renderer_;
    SoundBank& sounds_;
    Progress& progress_;
    const platformer::Level& level_;
    int index_;

    World world_;
    Input input_;
    float accumulator_ = 0.f;
    std::optional<Dialogue> dialogue_;
    std::size_t typedChars_ = 0;
    bool paused_ = false;
    int pauseSelection_ = 0;
    bool outroShown_ = false;
    float fadeIn_ = 1.f;
    float fadeOut_ = 0.f;
    float levelTime_ = 0.f;
};

}  // namespace

std::string_view buddyStorySection(int buddyId) { return buddyId == 1 ? "crude_glitch" : "pretty_shader"; }

StageResult runPlatformerStage(GameContext& ctx, const StageSetup& setup, PlatformerRenderer& renderer,
                               Progress& progress) {
    SoundBank sounds(setup.sound);
    for (int i = std::max(0, progress.level); i < static_cast<int>(setup.levels.size()); ++i) {
        progress.level = i;
        ctx.save();
        const platformer::Level level = loadLevel(ctx.assets, setup.levels[static_cast<std::size_t>(i)]);
        LevelSession session(ctx, setup, renderer, sounds, progress, level, i);
        if (!session.run()) return StageResult::Quit;
    }
    return StageResult::Completed;
}

}  // namespace pix::gfx
