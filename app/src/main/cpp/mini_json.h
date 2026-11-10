// ============================================================================
//  mini_json.h - 极简只读 JSON 解析器（C++11，无分配器定制、无异常）
// ============================================================================
//
//  SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//  Copyright (c) 2026 Kalyn-Voris
//
//  用途：解析 Live2D 模型配置（model3.json / motion3.json / exp3.json /
//  pose3.json）。这些文件由用户导入，格式固定且层级浅，
//  引 nlohmann/json 会给 .so 增加几百 KB，因此自带一个几百行的递归下降实现。
//
//  能力与边界（够用即止）：
//   - 支持 object / array / string / number / true / false / null；
//   - 字符串支持常见转义（\" \\ \/ \b \f \n \r \t）与 \uXXXX（仅 BMP，
//     超出范围按 UTF-16 代理对拆成两个 UTF-8 序列，本工程用不到）；
//   - 数字统一走 strtod（双精度）；
//   - 不做注释、尾逗号、NaN/Infinity（JSON 标准本来也不允许）；
//   - 递归深度上限 64，超过直接判失败，防畸形输入打爆栈。
//
//  所有权：解析结果是一棵持有自身存储的树。Value 里数组元素与对象值共用
//  items，对象的键存在 keys（与 items 下标一一对应），因此遍历对象必须
//  同时读 keys/items。
// ============================================================================
#pragma once

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace mjson {

enum class Type { Null, Bool, Number, String, Array, Object };

struct Value {
    Type type = Type::Null;
    bool boolean = false;
    double number = 0.0;
    std::string str;              // String 的值，或 Object 的键（键另存 keys）
    std::vector<Value> items;     // Array 元素 / Object 的值
    std::vector<std::string> keys; // Object 的键，与 items 对齐

    bool isObject() const { return type == Type::Object; }
    bool isArray() const { return type == Type::Array; }
    bool isNumber() const { return type == Type::Number; }

    /** 对象取键；不存在或类型不符返回 nullptr。 */
    const Value *find(const char *key) const {
        if (type != Type::Object) return nullptr;
        for (size_t i = 0; i < keys.size(); ++i) {
            if (keys[i] == key) return &items[i];
        }
        return nullptr;
    }

    /** 数字取值；类型不符时返回 fallback。 */
    double numberOr(double fallback) const {
        return type == Type::Number ? number : fallback;
    }

    /** 字符串取值；类型不符返回空串。 */
    const std::string &stringOrEmpty() const {
        static const std::string kEmpty;
        return type == Type::String ? str : kEmpty;
    }

    /** 布尔取值；类型不符返回 fallback。 */
    bool boolOr(bool fallback) const {
        return type == Type::Bool ? boolean : fallback;
    }
};

class Parser {
public:
    explicit Parser(const std::string &text) : text_(text) {}

    /** 解析整份文本；尾部只允许空白。成功时写入 out。 */
    bool parse(Value *out) {
        if (!out) return false;
        pos_ = 0;
        ok_ = true;
        skipWhitespace();
        parseValue(out, 0);
        if (!ok_) return false;
        skipWhitespace();
        // 允许 BOM/尾随空白之外的残留：严格判定，避免半截文件被当成合法
        return pos_ >= text_.size();
    }

private:
    static constexpr int kMaxDepth = 64;

    const std::string &text_;
    size_t pos_ = 0;
    bool ok_ = true;

    bool eof() const { return pos_ >= text_.size(); }
    char peek() const { return eof() ? '\0' : text_[pos_]; }

    void skipWhitespace() {
        while (!eof()) {
            const char c = text_[pos_];
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                ++pos_;
            } else if ((unsigned char) c == 0xEF && pos_ + 2 < text_.size() &&
                       (unsigned char) text_[pos_ + 1] == 0xBB &&
                       (unsigned char) text_[pos_ + 2] == 0xBF) {
                pos_ += 3; // UTF-8 BOM
            } else {
                return;
            }
        }
    }

    bool consume(char expected) {
        skipWhitespace();
        if (peek() != expected) {
            ok_ = false;
            return false;
        }
        ++pos_;
        return true;
    }

    static void appendUtf8(std::string *dst, unsigned int cp) {
        if (cp <= 0x7F) {
            dst->push_back((char) cp);
        } else if (cp <= 0x7FF) {
            dst->push_back((char) (0xC0 | (cp >> 6)));
            dst->push_back((char) (0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            dst->push_back((char) (0xE0 | (cp >> 12)));
            dst->push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            dst->push_back((char) (0x80 | (cp & 0x3F)));
        } else {
            dst->push_back((char) (0xF0 | (cp >> 18)));
            dst->push_back((char) (0x80 | ((cp >> 12) & 0x3F)));
            dst->push_back((char) (0x80 | ((cp >> 6) & 0x3F)));
            dst->push_back((char) (0x80 | (cp & 0x3F)));
        }
    }

    bool parseHex4(unsigned int *out) {
        if (pos_ + 4 > text_.size()) return false;
        unsigned int v = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = text_[pos_++];
            v <<= 4;
            if (c >= '0' && c <= '9') v |= (unsigned int) (c - '0');
            else if (c >= 'a' && c <= 'f') v |= (unsigned int) (c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') v |= (unsigned int) (c - 'A' + 10);
            else return false;
        }
        *out = v;
        return true;
    }

    bool parseString(std::string *out) {
        if (!consume('"')) return false;
        out->clear();
        while (!eof()) {
            const char c = text_[pos_++];
            if (c == '"') return true;
            if (c != '\\') {
                out->push_back(c);
                continue;
            }
            if (eof()) break;
            const char esc = text_[pos_++];
            switch (esc) {
                case '"': out->push_back('"'); break;
                case '\\': out->push_back('\\'); break;
                case '/': out->push_back('/'); break;
                case 'b': out->push_back('\b'); break;
                case 'f': out->push_back('\f'); break;
                case 'n': out->push_back('\n'); break;
                case 'r': out->push_back('\r'); break;
                case 't': out->push_back('\t'); break;
                case 'u': {
                    unsigned int cp = 0;
                    if (!parseHex4(&cp)) {
                        ok_ = false;
                        return false;
                    }
                    // 代理对：高代理后紧跟 \uXXXX 时合并
                    if (cp >= 0xD800 && cp <= 0xDBFF && pos_ + 6 <= text_.size() &&
                        text_[pos_] == '\\' && text_[pos_ + 1] == 'u') {
                        const size_t save = pos_;
                        pos_ += 2;
                        unsigned int lo = 0;
                        if (parseHex4(&lo) && lo >= 0xDC00 && lo <= 0xDFFF) {
                            cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                        } else {
                            pos_ = save; // 不成对：按孤立代理输出
                        }
                    }
                    appendUtf8(out, cp);
                    break;
                }
                default:
                    ok_ = false;
                    return false;
            }
        }
        ok_ = false;
        return false;
    }

    void parseNumber(Value *out) {
        const size_t start = pos_;
        if (peek() == '-' || peek() == '+') ++pos_;
        while (!eof()) {
            const char c = text_[pos_];
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' ||
                c == '+' || c == '-') {
                ++pos_;
            } else {
                break;
            }
        }
        if (pos_ == start) {
            ok_ = false;
            return;
        }
        const std::string token = text_.substr(start, pos_ - start);
        char *end = nullptr;
        const double v = std::strtod(token.c_str(), &end);
        if (!end || *end != '\0') {
            ok_ = false;
            return;
        }
        out->type = Type::Number;
        out->number = v;
    }

    void parseLiteral(const char *literal, Type type, bool boolean, Value *out) {
        const size_t len = std::strlen(literal);
        if (text_.compare(pos_, len, literal) != 0) {
            ok_ = false;
            return;
        }
        pos_ += len;
        out->type = type;
        out->boolean = boolean;
    }

    void parseValue(Value *out, int depth) {
        if (!ok_) return;
        if (depth > kMaxDepth) {
            ok_ = false;
            return;
        }
        skipWhitespace();
        switch (peek()) {
            case '{': parseObject(out, depth); break;
            case '[': parseArray(out, depth); break;
            case '"': {
                out->type = Type::String;
                if (!parseString(&out->str)) ok_ = false;
                break;
            }
            case 't': parseLiteral("true", Type::Bool, true, out); break;
            case 'f': parseLiteral("false", Type::Bool, false, out); break;
            case 'n': parseLiteral("null", Type::Null, false, out); break;
            default: parseNumber(out); break;
        }
    }

    void parseArray(Value *out, int depth) {
        if (!consume('[')) return;
        out->type = Type::Array;
        skipWhitespace();
        if (peek() == ']') {
            ++pos_;
            return;
        }
        while (ok_) {
            Value child;
            parseValue(&child, depth + 1);
            if (!ok_) return;
            out->items.push_back(std::move(child));
            skipWhitespace();
            if (peek() == ',') {
                ++pos_;
                continue;
            }
            if (!consume(']')) ok_ = false;
            return;
        }
    }

    void parseObject(Value *out, int depth) {
        if (!consume('{')) return;
        out->type = Type::Object;
        skipWhitespace();
        if (peek() == '}') {
            ++pos_;
            return;
        }
        while (ok_) {
            std::string key;
            if (!parseString(&key)) {
                ok_ = false;
                return;
            }
            if (!consume(':')) return;
            Value child;
            parseValue(&child, depth + 1);
            if (!ok_) return;
            out->keys.push_back(std::move(key));
            out->items.push_back(std::move(child));
            skipWhitespace();
            if (peek() == ',') {
                ++pos_;
                continue;
            }
            if (!consume('}')) ok_ = false;
            return;
        }
    }
};

/** 便捷入口：整串解析，失败返回 false 且不修改 out。 */
inline bool parse(const std::string &text, Value *out) {
    Value tmp;
    Parser parser(text);
    if (!parser.parse(&tmp)) return false;
    *out = std::move(tmp);
    return true;
}

}  // namespace mjson
