#pragma once

// Стадия 4 — «Выход в реальность».
// Разрешение растёт, пиксели мельчают и исчезают — Тимур возвращается домой.
// Дальше игра открывает настоящее видео (assets/video/finale.mp4), где трое актёров
// играют Тимура, Алину и Егора. Пока видео не снято, та же сцена показывается текстом
// (секция epilogue в story.txt). В конце — титры со статистикой прохождения.

#include "core/Progress.hpp"
#include "gfx/Gfx.hpp"

namespace pix::gfx {

enum class FinaleResult { Finished, Quit };

FinaleResult runFinale(GameContext& ctx, const Progress& progress);

}  // namespace pix::gfx
