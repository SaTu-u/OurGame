#include "doctest.h"

#include "core/Utf8.hpp"

using namespace pix;

TEST_CASE("utf8: длина считается в символах, а не в байтах") {
    CHECK(utf8::length("abc") == 3);
    CHECK(utf8::length("Пиксель") == 7);
    CHECK(utf8::length("") == 0);
}

TEST_CASE("utf8: prefix не разрезает русские буквы пополам") {
    CHECK(utf8::prefix("Привет", 3) == "При");
    CHECK(utf8::prefix("Привет", 0) == "");
    CHECK(utf8::prefix("Привет", 100) == "Привет");
}

TEST_CASE("utf8: decode и encode — взаимно обратны") {
    const std::string text = "Ж > █ ok";
    std::string back;
    for (char32_t c : utf8::decode(text)) utf8::append(back, c);
    CHECK(back == text);
    CHECK(utf8::decode("Ж")[0] == U'Ж');
}

TEST_CASE("utf8: битые байты не роняют декодер") {
    const auto decoded = utf8::decode(std::string("\xD0", 1));
    REQUIRE(decoded.size() == 1);
    CHECK(decoded[0] == U'�');
}

TEST_CASE("utf8: перенос по словам") {
    const auto lines = utf8::wrap("раз два три четыре", 8);
    REQUIRE(lines.size() == 3);
    CHECK(lines[0] == "раз два");
    CHECK(lines[1] == "три");
    CHECK(lines[2] == "четыре");
    for (const auto& l : utf8::wrap("оченьоченьдлинноеслово", 5)) CHECK(utf8::length(l) <= 5);
}
