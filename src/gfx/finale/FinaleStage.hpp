#pragma once

// Стадия 4 — «Выход в реальность».
// Разрешение растёт, пиксели мельчают и исчезают, а потом игра открывает
// настоящее видео (assets/video/finale.mp4), где команда играет героя и его кентов.
// После видео — титры со статистикой прохождения.

#include "core/Progress.hpp"
#include "gfx/Gfx.hpp"

namespace pix::gfx {

enum class FinaleResult { Finished, Quit };

FinaleResult runFinale(GameContext& ctx, const Progress& progress);

}  // namespace pix::gfx
