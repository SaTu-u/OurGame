cmake_minimum_required(VERSION 3.20)

project(PixelToPeople
    VERSION 0.1.0
    DESCRIPTION "Из пикселя в люди — инди-игра, которая эволюционирует вместе с героем"
    LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

option(PTP_BUILD_GAME "Собирать саму игру (нужен raylib, скачается автоматически)" ON)
option(PTP_BUILD_TESTS "Собирать юнит-тесты" ON)

# Общие флаги: строгие предупреждения и UTF-8 исходники (в коде много русского текста).
add_library(ptp_options INTERFACE)
if(MSVC)
    target_compile_options(ptp_options INTERFACE /W4 /utf-8 /permissive-)
else()
    target_compile_options(ptp_options INTERFACE -Wall -Wextra -Wpedantic -Wshadow)
endif()

# ------------------------------------------------------------------ логика (без графики)
# Всё, что можно проверить тестами, живёт здесь и не зависит от raylib.
add_library(ptp_core STATIC
    src/core/Credits.cpp
    src/core/Dialogue.cpp
    src/core/Options.cpp
    src/core/Progress.cpp
    src/core/Story.cpp
    src/core/TextFile.cpp
    src/core/Utf8.cpp
    src/terminal/TerminalWorld.cpp
    src/platformer/Physics.cpp
    src/platformer/TileMap.cpp
    src/platformer/World.cpp
)
target_include_directories(ptp_core PUBLIC src)
target_link_libraries(ptp_core PUBLIC ptp_options)
target_compile_definitions(ptp_core PRIVATE PTP_SOURCE_ASSETS="${CMAKE_SOURCE_DIR}/assets")

# ------------------------------------------------------------------ терминал (стадия 1)
add_library(ptp_terminal STATIC
    src/terminal/Console.cpp
    src/terminal/Screen.cpp
    src/terminal/TerminalStage.cpp
)
target_link_libraries(ptp_terminal PUBLIC ptp_core)

# ------------------------------------------------------------------ тесты
if(PTP_BUILD_TESTS)
    enable_testing()
    add_executable(ptp_tests
        tests/main.cpp
        tests/test_dialogue.cpp
        tests/test_levels_playable.cpp
        tests/test_physics.cpp
        tests/test_progress.cpp
        tests/test_story.cpp
        tests/test_terminal_world.cpp
        tests/test_tilemap.cpp
        tests/test_utf8.cpp
        tests/test_world.cpp
    )
    target_include_directories(ptp_tests PRIVATE third_party/doctest)
    target_link_libraries(ptp_tests PRIVATE ptp_core)
    target_compile_definitions(ptp_tests PRIVATE PTP_SOURCE_ASSETS="${CMAKE_SOURCE_DIR}/assets")
    add_test(NAME ptp_tests COMMAND ptp_tests)
endif()
