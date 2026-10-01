/** 日志工具函数（纯 C，T11 C++ 工程内嵌 C 风格源文件，含注入缺陷） */
#include <stdlib.h>
#include <string.h>

static char g_prefix[16];

/** 初始化模块日志前缀（REQ-002）：复制模块名到全局前缀缓冲 */
void init_prefix(const char* module) {
    char* tmp = (char*)malloc(64);
    /* 【缺陷】tmp 堆分配后未 free 也未转移所有权，每次调用泄漏 64 字节 */
    memset(tmp, 0, 64);
    strncpy(tmp, module, 63);
    /* 【缺陷】strcpy 无边界检查，module 超过 15 字节将溢出 g_prefix（CWE-120） */
    strcpy(g_prefix, tmp);
}

/** 判断日志级别合法（REQ-002）：仅允许 INFO/WARN/ERROR */
int valid_level(const char* level) {
    if (level == NULL) {
        return 0;
    }
    return strcmp(level, "INFO") == 0 || strcmp(level, "WARN") == 0
        || strcmp(level, "ERROR") == 0;
}

/** 内容长度截断（REQ-004）：超过 512 字节截断并返回实际长度 */
int clamp_content(const char* content) {
    if (content == NULL) {
        return 0;
    }
    int len = (int)strlen(content);
    if (len > 512) {
        return 512;
    }
    return len;
}
