#pragma once

// Титры из assets/credits.txt. Имена команды правятся в файле, а не в коде.
//
//   [В ролях]
//   Пиксель | Тимур «SaTu»
//   просто строка без «|» — выводится по центру

#include <string>
#include <string_view>
#include <vector>

namespace pix {

struct CreditEntry {
    std::string role;  ///< пусто — строка без роли
    std::string name;
};

struct CreditSection {
    std::string title;
    std::vector<CreditEntry> entries;
};

std::vector<CreditSection> parseCredits(std::string_view text);

}  // namespace pix
