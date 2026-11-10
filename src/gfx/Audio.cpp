#include "gfx/Audio.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <initializer_list>
#include <random>
#include <utility>
#include <vector>

namespace pix::gfx {

namespace {

constexpr int kRate = 22050;
constexpr float kTau = 6.2831853f;

using Samples = std::vector<float>;
using Osc = float (*)(float phase);

float square(float p) { return p < 0.5f ? 0.6f : -0.6f; }
float triangle(float p) { return 4.f * std::abs(p - 0.5f) - 1.f; }
float sine(float p) { return std::sin(p * kTau); }

/// Тон, частота которого плавно меняется от f0 до f1.
Samples sweep(float duration, float f0, float f1, Osc osc, float decay = 1.5f) {
    const int n = static_cast<int>(duration * kRate);
    Samples out(static_cast<std::size_t>(n));
    float phase = 0.f;
    for (int i = 0; i < n; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(n);
        const float f = f0 + (f1 - f0) * t;
        phase += f / kRate;
        phase -= std::floor(phase);
        const float attack = std::min(1.f, static_cast<float>(i) / (0.004f * kRate));
        out[static_cast<std::size_t>(i)] = osc(phase) * attack * std::pow(1.f - t, decay);
    }
    return out;
}

/// Последовательность нот (частота, длительность).
Samples notes(std::initializer_list<std::pair<float, float>> seq, Osc osc) {
    Samples out;
    for (const auto& [freq, dur] : seq) {
        const Samples part = sweep(dur, freq, freq, osc, 0.8f);
        out.insert(out.end(), part.begin(), part.end());
    }
    return out;
}

Samples noise(float duration, float decay, unsigned seed, int hold = 1) {
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> dist(-1.f, 1.f);
    const int n = static_cast<int>(duration * kRate);
    Samples out(static_cast<std::size_t>(n));
    float value = 0.f;
    for (int i = 0; i < n; ++i) {
        if (i % hold == 0) value = dist(rng);  // hold > 1 — «грубый» низкий шум
        const float t = static_cast<float>(i) / static_cast<float>(n);
        out[static_cast<std::size_t>(i)] = value * std::pow(1.f - t, decay) * 0.7f;
    }
    return out;
}

Samples mixed(const Samples& a, const Samples& b, float gainB = 1.f) {
    Samples out(std::max(a.size(), b.size()), 0.f);
    for (std::size_t i = 0; i < a.size(); ++i) out[i] += a[i];
    for (std::size_t i = 0; i < b.size(); ++i) out[i] += b[i] * gainB;
    return out;
}

/// «Восьмибитность»: грубое квантование громкости.
void crush(Samples& s) {
    for (float& v : s) v = std::round(v * 6.f) / 6.f;
}

/// Простое эхо для красивого 2D.
void echo(Samples& s) {
    const auto d1 = static_cast<std::size_t>(0.09f * kRate);
    const auto d2 = static_cast<std::size_t>(0.18f * kRate);
    s.resize(s.size() + d2, 0.f);
    for (std::size_t i = s.size(); i-- > d1;) {
        s[i] += s[i - d1] * 0.3f;
        if (i >= d2) s[i] += s[i - d2] * 0.12f;
    }
}

Samples design(Sfx sfx, SfxStyle style) {
    const bool crude = style == SfxStyle::Crude;
    const Osc tone = crude ? square : sine;
    const Osc soft = crude ? square : triangle;
    switch (sfx) {
        case Sfx::Jump: return sweep(0.14f, 280.f, 620.f, tone);
        case Sfx::AirJump: return sweep(0.14f, 480.f, 980.f, tone);
        case Sfx::Land: return noise(0.06f, 3.f, 1, crude ? 8 : 3);
        case Sfx::Pickup: return notes({{880.f, 0.05f}, {1320.f, 0.09f}}, soft);
        case Sfx::Stomp: return mixed(sweep(0.15f, 420.f, 90.f, tone), noise(0.08f, 2.f, 2, 4), 0.6f);
        case Sfx::Hurt: return mixed(sweep(0.25f, 320.f, 70.f, soft), noise(0.2f, 2.f, 3, 2), 0.5f);
        case Sfx::Death: return sweep(0.7f, 620.f, 40.f, tone, 1.f);
        case Sfx::Dash: return mixed(noise(0.18f, 1.5f, 4, crude ? 6 : 1), sweep(0.18f, 200.f, 500.f, soft), 0.4f);
        case Sfx::Checkpoint: return notes({{523.f, 0.07f}, {659.f, 0.07f}, {784.f, 0.16f}}, soft);
        case Sfx::Buddy: return notes({{392.f, 0.08f}, {523.f, 0.08f}, {659.f, 0.08f}, {1046.f, 0.25f}}, soft);
        case Sfx::BossHit: return mixed(sweep(0.3f, 160.f, 40.f, tone, 1.f), noise(0.3f, 1.5f, 5, 6), 0.7f);
        case Sfx::BossSlam: return mixed(noise(0.45f, 1.2f, 6, 12), sweep(0.4f, 90.f, 30.f, sine, 1.f), 0.9f);
        case Sfx::Shoot: return sweep(0.1f, 240.f, 120.f, soft);
        case Sfx::Blip: return sweep(0.025f, crude ? 600.f : 1400.f, crude ? 600.f : 1300.f, soft, 2.f);
        case Sfx::Select: return notes({{660.f, 0.04f}, {990.f, 0.06f}}, soft);
        case Sfx::Complete:
            return notes({{523.f, 0.1f}, {659.f, 0.1f}, {784.f, 0.1f}, {1046.f, 0.12f}, {784.f, 0.08f}, {1046.f, 0.35f}},
                         soft);
        case Sfx::Count: break;
    }
    return {};
}

Sound toSound(Samples samples, SfxStyle style) {
    if (style == SfxStyle::Crude) crush(samples);
    else echo(samples);

    std::vector<std::int16_t> pcm(samples.size());
    for (std::size_t i = 0; i < samples.size(); ++i) {
        pcm[i] = static_cast<std::int16_t>(std::clamp(samples[i], -1.f, 1.f) * 30000.f);
    }
    Wave wave{};
    wave.frameCount = static_cast<unsigned int>(pcm.size());
    wave.sampleRate = kRate;
    wave.sampleSize = 16;
    wave.channels = 1;
    wave.data = pcm.data();
    return LoadSoundFromWave(wave);  // raylib копирует данные себе
}

}  // namespace

SoundBank::SoundBank(SfxStyle style) {
    if (!IsAudioDeviceReady()) return;  // нет звуковой карты — играем молча
    volume_ = style == SfxStyle::Crude ? 0.3f : 0.6f;  // квадратная волна очень громкая
    for (std::size_t i = 0; i < sounds_.size(); ++i) {
        sounds_[i] = toSound(design(static_cast<Sfx>(i), style), style);
    }
    loaded_ = true;
}

SoundBank::~SoundBank() {
    if (!loaded_) return;
    for (auto& s : sounds_) UnloadSound(s);
}

void SoundBank::play(Sfx sfx, float pitch, float volume) {
    if (!loaded_) return;
    Sound& s = sounds_[static_cast<std::size_t>(sfx)];
    SetSoundPitch(s, pitch);
    SetSoundVolume(s, volume * volume_);
    PlaySound(s);
}

}  // namespace pix::gfx
