#include "core/Options.hpp"

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
    return options;
}

std::string usageText() {
    return "Из пикселя в люди — инди-игра, которая эволюционирует вместе с героем.\n"
           "\n"
           "Использование: pixel_to_people [параметры]\n"
           "  --stage N    начать со стадии N:\n"
           "               1 terminal — консоль, ты пиксель\n"
           "               2 crude    — стрёмное 2D\n"
           "               3 pretty   — красивое 2D\n"
           "               4 finale   — выход в реальность\n"
           "  --new        начать заново, не загружая сохранение\n"
           "  --no-video   не открывать финальное видео\n"
           "  --help       показать эту справку\n";
}

}  // namespace pix
