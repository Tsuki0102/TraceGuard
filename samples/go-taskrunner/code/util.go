/** 任务工具函数（T11 Go 演示工程，含注入缺陷） */
package main

import (
	"fmt"
	"strconv"
	"strings"
)

// ParseSize 解析任务大小字符串（REQ-003）：支持 "100" / "100KB"
func ParseSize(text string) int {
	if len(text) == 0 {
		/* 【缺陷】工具函数 panic 滥用，应返回 error */
		panic("size text is empty")
	}
	upper := strings.ToUpper(text)
	if strings.HasSuffix(upper, "KB") {
		base := strings.TrimSuffix(upper, "KB")
		n, convErr := strconv.Atoi(base)
		if convErr != nil {
			return 0
		}
		return n * 1024
	}
	n, convErr := strconv.Atoi(upper)
	if convErr != nil {
		return 0
	}
	return n
}

// ValidLevel 校验任务级别合法（REQ-002）：仅允许 INFO/WARN/ERROR
func ValidLevel(level string) bool {
	return level == "INFO" || level == "WARN" || level == "ERROR"
}

// LevelWeight 按级别计算权重（REQ-002）：INFO=1 / WARN=2 / ERROR=3
func LevelWeight(level string) int {
	if level == "ERROR" {
		return 3
	}
	if level == "WARN" {
		return 2
	}
	return 1
}

// FormatSummary 生成任务摘要（REQ-007）
func FormatSummary(name string, count int) string {
	return fmt.Sprintf("task %s total %d", name, count)
}
