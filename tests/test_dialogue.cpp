#include "doctest.h"

#include "core/Dialogue.hpp"

using namespace pix;

TEST_CASE("dialogue: печатная машинка и пропуск") {
    Dialogue d({{"Глюк", "Привет"}, {"", "Пока"}}, 10.f);
    CHECK_FALSE(d.finished());
    CHECK(d.visibleText().empty());

    d.update(0.3f);  // 3 символа
    CHECK(d.visibleText() == "При");
    CHECK_FALSE(d.lineFullyShown());

    d.advance();  // первое нажатие — показать строку целиком
    CHECK(d.visibleText() == "Привет");
    CHECK(d.lineFullyShown());

    d.advance();  // второе — следующая строка
    CHECK(d.index() == 1);
    CHECK(d.current().text == "Пока");

    d.update(10.f);
    d.advance();
    CHECK(d.finished());
}

TEST_CASE("dialogue: пустой диалог сразу закончен") {
    Dialogue d({});
    CHECK(d.finished());
    d.advance();
    d.update(1.f);
    CHECK(d.finished());
}
