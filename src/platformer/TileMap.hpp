#pragma once

// Карта уровня из обычного текстового файла (assets/levels/*.txt).
// Уровни рисуются прямо символами — их легко править в любом редакторе.
//
//   #  блок            ^  шипы          P  старт игрока
//   x  жук             *  пиксель       F  финиш (разлом на следующий уровень)
//   C  чекпоинт        S  пружина       1  Алина      2  Егор
//   !  сюжетная точка: разговор начинается, когда герой проходит мимо
//   ?  осколок памяти: его надо подобрать, чтобы прочитать
//   .  или пробел — пусто
//
// Служебные строки в начале файла:
//   @title Название уровня
//   @intro имя_секции_из_story.txt        — разговор в начале уровня
//   @talk  имя_секции                     — для каждого «!», слева направо
//   @shard имя_секции                     — для каждого «?», слева направо
//   ; комментарий

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "platformer/Math.hpp"

namespace pix::platformer {

enum class Tile : std::uint8_t { Empty, Solid, Spike };

struct Spawn {
    char symbol;
    int x;
    int y;
};

class TileMap {
public:
    TileMap() = default;
    /// Бросает std::runtime_error, если карта пустая.
    static TileMap parse(const std::vector<std::string>& rows);

    [[nodiscard]] int width() const { return width_; }
    [[nodiscard]] int height() const { return height_; }
    /// За левым и правым краем — стена, сверху — небо, снизу — пропасть.
    [[nodiscard]] Tile at(int x, int y) const;
    [[nodiscard]] bool solid(int x, int y) const { return at(x, y) == Tile::Solid; }
    [[nodiscard]] const std::vector<Spawn>& spawns() const { return spawns_; }
    [[nodiscard]] std::optional<Spawn> find(char symbol) const;
    /// Все точки с данным символом, отсортированные слева направо (при равном x — сверху вниз).
    [[nodiscard]] std::vector<Spawn> all(char symbol) const;

private:
    int width_ = 0;
    int height_ = 0;
    std::vector<Tile> tiles_;
    std::vector<Spawn> spawns_;
};

struct Level {
    std::string title;
    std::string intro;                ///< секция сюжета, которая показывается в начале уровня
    std::vector<std::string> talks;   ///< секции для сюжетных точек «!», слева направо
    std::vector<std::string> shards;  ///< секции для осколков памяти «?», слева направо
    TileMap map;
};

/// Разбирает файл уровня вместе со служебными строками.
Level parseLevel(const std::vector<std::string>& lines);

}  // namespace pix::platformer
