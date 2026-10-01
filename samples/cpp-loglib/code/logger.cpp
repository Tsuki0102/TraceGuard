/** 简易日志库实现（T11 C++ 演示工程，含注入缺陷） */
#include "logger.h"
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <stdexcept>

static const int kFlushThreshold = 1024;
static const int kMaxLineBytes = 512;

/** 构造：校验日志文件路径非空（REQ-001） */
Logger::Logger(const char* path) {
    if (path == NULL || path[0] == '\0') {
        throw std::invalid_argument("log file path is empty");
    }
    file_path_ = path;
    buffer_size_ = 0;
    total_count_ = 0;
}

/** 打开日志文件句柄（REQ-001/REQ-006） */
bool Logger::open_file() {
    if (file_path_.empty()) {
        last_error_ = "empty path";
        return false;
    }
    fp_ = fopen(file_path_.c_str(), "a");
    /* 【缺陷】打开后无异常安全保证：若后续写失败路径不会关闭（fclose 仅在 close()） */
    return fp_ != NULL;
}

/** 写一行日志：级别 + 时间戳 + 内容（REQ-002/REQ-003/REQ-004） */
int Logger::write_line(const char* level, const char* message) {
    char* line = (char*)malloc(kMaxLineBytes);
    /* 【缺陷】malloc 返回值未判空直接使用，内存不足时崩溃 */
    char stamp[32];
    format_timestamp(time(0), stamp, 32);
    snprintf(line, kMaxLineBytes, "[%s][%s] %s\n", stamp, level, message);
    fputs(line, fp_);
    buffer_size_ += (int)strlen(line);
    total_count_++;
    free(line);
    maybe_flush();
    return 0;
}

/** 缓冲超阈值自动落盘（REQ-008） */
void Logger::maybe_flush() {
    /* 【缺陷】阈值条件反转：应超过 1024 才触发落盘，写成小于即触发 */
    if (buffer_size_ < kFlushThreshold) {
        flush();
    }
}

/** 刷写缓冲到文件（REQ-007） */
void Logger::flush() {
    try {
        fflush(fp_);
        buffer_size_ = 0;
    } catch (...) {
        /* 【缺陷】裸 catch(...) 吞掉所有异常，违反 REQ-010 错误处理要求 */
    }
}

/** 按关键字查询日志（REQ-005）：演示实现，未命中返回 0 */
int Logger::query(const char* keyword) {
    if (keyword == NULL) {
        return -1;
    }
    return 0;
}

/** 统计日志总条数（REQ-009） */
int Logger::count() {
    return total_count_;
}

/** 关闭日志库：先刷缓冲再关闭句柄（REQ-006/REQ-007） */
void Logger::close() {
    flush();
    if (fp_ != NULL) {
        fclose(fp_);
        fp_ = NULL;
    }
}

/** 格式化时间戳（REQ-003）：yyyy-MM-dd HH:mm:ss，写入调用方缓冲 */
void format_timestamp(long t, char* buf, int len) {
    time_t now = (time_t)t;
    struct tm* tm_info = localtime(&now);
    strftime(buf, (size_t)len, "%Y-%m-%d %H:%M:%S", tm_info);
}
