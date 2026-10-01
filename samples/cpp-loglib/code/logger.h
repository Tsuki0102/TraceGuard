/** 简易日志库接口声明（T11 C++ 演示工程） */
#ifndef LOGGER_H
#define LOGGER_H

#include <cstdio>
#include <string>

/** 日志器：文件输出 + 级别标识 + 内存缓冲（REQ-001~REQ-009） */
class Logger {
public:
    explicit Logger(const char* path);
    bool open_file();
    int write_line(const char* level, const char* message);
    void maybe_flush();
    void flush();
    int query(const char* keyword);
    int count();
    void close();

private:
    std::string file_path_;
    FILE* fp_ = NULL;
    int buffer_size_ = 0;
    int total_count_ = 0;
    std::string last_error_;
};

/** C 风格自由函数：时间戳格式化（REQ-003），写入调用方提供的缓冲 */
void format_timestamp(long t, char* buf, int len);

#endif
