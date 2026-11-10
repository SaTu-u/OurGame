#include "platformer/TileMap.hpp"

#include <algorithm>
#include <stdexcept>

namespace pix::platformer {

TileMap TileMap::parse(const std::vector<std::string>& rows) {
    TileMap map;
    map.height_ = static_cast<int>(rows.size());
    for (const auto& row : rows) map.width_ = std::max(map.width_, static_cast<int>(row.size()));
    if (map.width_ == 0 || map.height_ == 0) throw std::runtime_error("пустая карта уровня");

    map.tiles_.assign(static_cast<std::size_t>(map.width_ * map.height_), Tile::Empty);
    for (int y = 0; y < map.height_; ++y) {
        const auto& row = rows[static_cast<std::size_t>(y)];
        for (int x = 0; x < static_cast<int>(row.size()); ++x) {
            const char c = row[static_cast<std::size_t>(x)];
            Tile& tile = map.tiles_[static_cast<std::size_t>(y * map.width_ + x)];
            switch (c) {
                case '#': tile = Tile::Solid; break;
                case '^': tile = Tile::Spike; break;
                case 'P':
                case 'x':
                case '*':
                case 'F':
                case 'C':
                case '1':
                case '2':
                case 'B': map.spawns_.push_back({c, x, y}); break;
                default: break;  // всё остальное — воздух
            }
        }
    }
    return map;
}

Tile TileMap::at(int x, int y) const {
    if (x < 0 || x >= width_) return Tile::Solid;
    if (y < 0 || y >= height_) return Tile::Empty;
    return tiles_[static_cast<std::size_t>(y * width_ + x)];
}

std::optional<Spawn> TileMap::find(char symbol) const {
    const auto it = std::find_if(spawns_.begin(), spawns_.end(), [symbol](const Spawn& s) { return s.symbol == symbol; });
    if (it == spawns_.end()) return std::nullopt;
    return *it;
}

Level parseLevel(const std::vector<std::string>& lines) {
    Level level;
    std::vector<std::string> rows;
    bool mapStarted = false;
    for (const auto& line : lines) {
        if (!mapStarted) {
            if (line.rfind("@title ", 0) == 0) {
                level.title = line.substr(7);
                continue;
            }
            if (line.rfind("@intro ", 0) == 0) {
                level.intro = line.substr(7);
                continue;
            }
            if (line.empty() || line.front() == ';') continue;
        }
        mapStarted = true;
        rows.push_back(line);
    }
    // Пустые строки в конце файла картой не считаем.
    while (!rows.empty() && rows.back().find_first_not_of(" \t") == std::string::npos) rows.pop_back();
    level.map = TileMap::parse(rows);
    if (!level.map.find('P')) throw std::runtime_error("на уровне нет старта игрока 'P'");
    return level;
}

}  // namespace pix::platformer
