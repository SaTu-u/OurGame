#include "core/Utf8.hpp"

namespace pix::utf8 {

namespace {

bool isContinuation(unsigned char c) { return (c & 0xC0u) == 0x80u; }

/// Сколько байт занимает символ, начинающийся с байта lead (1..4), 0 — битый байт.
std::size_t sequenceLength(unsigned char lead) {
    if (lead < 0x80u) return 1;
    if ((lead & 0xE0u) == 0xC0u) return 2;
    if ((lead & 0xF0u) == 0xE0u) return 3;
    if ((lead & 0xF8u) == 0xF0u) return 4;
    return 0;
}

}  // namespace

std::size_t length(std::string_view s) {
    std::size_t count = 0;
    for (unsigned char c : s) {
        if (!isContinuation(c)) ++count;
    }
    return count;
}

std::string prefix(std::string_view s, std::size_t n) {
    std::size_t chars = 0;
    std::size_t i = 0;
    while (i < s.size()) {
        if (!isContinuation(static_cast<unsigned char>(s[i]))) {
            if (chars == n) break;
            ++chars;
        }
        ++i;
    }
    return std::string(s.substr(0, i));
}

std::u32string decode(std::string_view s) {
    std::u32string out;
    out.reserve(s.size());
    std::size_t i = 0;
    while (i < s.size()) {
        const auto lead = static_cast<unsigned char>(s[i]);
        const std::size_t len = sequenceLength(lead);
        if (len == 0 || i + len > s.size()) {
            out.push_back(U'�');
            ++i;
            continue;
        }
        char32_t cp = len == 1 ? lead : lead & (0xFFu >> (len + 1));
        bool ok = true;
        for (std::size_t k = 1; k < len; ++k) {
            const auto c = static_cast<unsigned char>(s[i + k]);
            if (!isContinuation(c)) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (c & 0x3Fu);
        }
        if (!ok) {
            out.push_back(U'�');
            ++i;
            continue;
        }
        out.push_back(cp);
        i += len;
    }
    return out;
}

void append(std::string& out, char32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::string encode(char32_t cp) {
    std::string out;
    append(out, cp);
    return out;
}

std::vector<std::string> wrap(std::string_view text, std::size_t width) {
    std::vector<std::string> lines;
    std::string line;
    std::size_t lineLength = 0;

    std::size_t pos = 0;
    while (pos < text.size()) {
        auto end = text.find(' ', pos);
        if (end == std::string_view::npos) end = text.size();
        std::string_view word = text.substr(pos, end - pos);
        pos = end + 1;
        if (word.empty()) continue;

        // Слишком длинное слово режем на куски.
        while (length(word) > width && width > 0) {
            if (!line.empty()) {
                lines.push_back(line);
                line.clear();
                lineLength = 0;
            }
            const std::string head = prefix(word, width);
            lines.push_back(head);
            word.remove_prefix(head.size());
        }
        const std::size_t wordLength = length(word);
        if (wordLength == 0) continue;
        if (lineLength > 0 && lineLength + 1 + wordLength > width) {
            lines.push_back(line);
            line.clear();
            lineLength = 0;
        }
        if (lineLength > 0) {
            line += ' ';
            ++lineLength;
        }
        line += word;
        lineLength += wordLength;
    }
    if (!line.empty() || lines.empty()) lines.push_back(line);
    return lines;
}

}  // namespace pix::utf8
