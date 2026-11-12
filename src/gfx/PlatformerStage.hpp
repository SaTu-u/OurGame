#pragma once

// Общий цикл для стадий 2 и 3. Главная идея архитектуры:
//
//     World (логика)  ──события──►  PlatformerRenderer (картинка)
//
// Мир один и тот же, а рендерер подставляется разный: CrudeRenderer рисует
// стрёмное 2D, PrettyRenderer — красивое. Это паттерн «Стратегия».

#include <string>
#include <string_view>
#include <vector>

#include "core/Dialogue.hpp"
#include "core/Progress.hpp"
#include "gfx/Audio.hpp"
#include "gfx/Gfx.hpp"
#include "platformer/TileMap.hpp"
#include "platformer/World.hpp"

namespace pix::gfx {

/// Что ещё, кроме мира, должен показать рендерер.
struct StageView {
    const Dialogue* dialogue = nullptr;
    bool paused = false;
    int pauseSelection = 0;
    std::string_view levelTitle;
    int levelIndex = 0;
    int levelCount = 1;
    float fade = 0.f;      ///< 0 — нет затемнения, 1 — чёрный экран
    float levelTime = 0.f;  ///< сколько секунд идёт уровень (для заставок)
    const Progress* progress = nullptr;
};

class PlatformerRenderer {
public:
    virtual ~PlatformerRenderer() = default;
    virtual void beginLevel(const platformer::World& world, const platformer::Level& level) = 0;
    virtual void onEvent(const platformer::WorldEvent& event, const platformer::World& world) = 0;
    virtual void update(float dt, const platformer::World& world) = 0;
    virtual void draw(const platformer::World& world, const StageView& view) = 0;
};

struct StageSetup {
    std::vector<std::string> levels;  ///< файлы в assets/levels
    platformer::PhysicsConfig physics;
    SfxStyle sound = SfxStyle::Crude;
    std::string outro;  ///< секция сюжета после последнего уровня (может быть пустой)
};

enum class StageResult { Completed, Quit };

StageResult runPlatformerStage(GameContext& ctx, const StageSetup& setup, PlatformerRenderer& renderer,
                               Progress& progress);

/// Какую секцию сюжета показать при первой встрече с другом (1 — Алина, 2 — Егор).
std::string_view buddyStorySection(int buddyId);

}  // namespace pix::gfx
