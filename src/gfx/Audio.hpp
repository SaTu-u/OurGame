#pragma once

// Звуки, которые синтезируются прямо в коде — ни одного аудиофайла.
// Стрёмное 2D пищит квадратной волной, как PC Speaker,
// красивое 2D звучит мягкими синусами с эхом. Одна и та же «нота» — разный стиль.

#include <raylib.h>

#include <array>
#include <cstddef>

namespace pix::gfx {

enum class Sfx {
    Jump,
    AirJump,
    Land,
    Pickup,
    Stomp,
    Hurt,
    Death,
    Dash,
    Checkpoint,
    Buddy,
    Spring,
    Shard,
    Blip,
    Select,
    Complete,
    Count,
};

enum class SfxStyle { Crude, Pretty };

class SoundBank {
public:
    explicit SoundBank(SfxStyle style);
    ~SoundBank();
    SoundBank(const SoundBank&) = delete;
    SoundBank& operator=(const SoundBank&) = delete;

    void play(Sfx sfx, float pitch = 1.f, float volume = 1.f);

private:
    std::array<Sound, static_cast<std::size_t>(Sfx::Count)> sounds_{};
    float volume_ = 0.6f;
    bool loaded_ = false;
};

}  // namespace pix::gfx
