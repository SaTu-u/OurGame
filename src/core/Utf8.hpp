#pragma once

// Мини-набор функций для работы с UTF-8.
// Весь текст в игре — на русском, поэтому «длина строки в байтах» нам не подходит:
// для печатной машинки в диалогах и для терминала нужны именно символы.

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

namespace pix::utf8 {

/// Количество символов (кодпоинтов) в строке.
std::size_t length(std::string_view s);

/// Первые n символов строки.
std::string prefix(std::string_view s, std::size_t n);

/// Декодирует строку в UTF-32. Битые байты заменяются на U+FFFD.
std::u32string decode(std::string_view s);

/// Дописывает символ cp в конец строки out в кодировке UTF-8.
void append(std::string& out, char32_t cp);

/// Кодирует один символ в UTF-8.
std::string encode(char32_t cp);

/// Переносит текст по словам так, чтобы строка была не длиннее width символов.
std::vector<std::string> wrap(std::string_view text, std::size_t width);

}  // namespace pix::utf8
