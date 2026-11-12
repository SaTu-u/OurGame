#include "core/Options.hpp"

#include <charconv>

namespace pix {

Options parseOptions(const std::vector<std::string>& args) {
    Options options;
    for (std::size_t i = 0; i < args.size(); ++i) {
        const std::string& arg = args[i];
        if (arg == "--help" || arg == "-h") {
            options.showHelp = true;
        } else if (arg == "--new") {
            options.fresh = true;
        } else if (arg == "--no-video") {
            options.noVideo = true;
        } else if (arg == "--demo") {
            options.demo = true;
        } else if (arg == "--level" || arg == "-l") {
            if (i + 1 >= args.size()) {
                options.errors.push_back("после --level нужен номер уровня (с 1)");
                continue;
            }
            const std::string& value = args[++i];
            int level = 0;
            const auto [ptr, ec] = std::from_chars(value.data(), value.data() + value.size(), level);
            if (ec != std::errc{} || ptr != value.data() + value.size() || level < 1) {
                options.errors.push_back("неверный номер уровня: " + value);
            } else {
                options.startLevel = level - 1;
            }
        } else if (arg == "--stage" || arg == "-s") {
            if (i + 1 >= args.size()) {
                options.errors.push_back("после --stage нужен номер стадии (1-4)");
                continue;
            }
            const std::string& value = args[++i];
            if (auto stage = parseStage(value)) {
                options.startStage = stage;
            } else {
                options.errors.push_back("неизвестная стадия: " + value);
            }
        } else {
            options.errors.push_back("неизвестный аргумент: " + arg);
        }
    }
    if (options.startLevel > 0 && !options.startStage) {
        options.errors.push_back("--level работает только вместе с --stage");
    }
    return options;
}

std::string usageText() {
    return "Из пикселя в люди — инди-игра, которая эволюционирует вместе с героем.\n"
           "\n"
           "Использование: pixel_to_people [параметры]\n"
           "  --stage N    начать со стадии N:\n"
           "               1 terminal — консоль, ты пиксель (3 сектора)\n"
           "               2 crude    — стрёмное 2D (4 уровня)\n"
           "               3 pretty   — красивое 2D (4 уровня)\n"
           "               4 finale   — выход в реальность\n"
           "  --level M    вместе с --stage: начать с уровня M этой стадии\n"
           "  --demo       режим показа: клавиша N пропускает уровень\n"
           "  --new        начать заново, не загружая сохранение\n"
           "  --no-video   не открывать финальное видео\n"
           "  --help       показать эту справку\n";
}

}  // namespace pix
