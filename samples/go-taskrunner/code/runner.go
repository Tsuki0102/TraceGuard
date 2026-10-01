/** 任务执行器服务层（T11 Go 演示工程，含注入缺陷） */
package main

import (
	"fmt"
	"os"
	"sync"
	"time"
)

const maxConcurrent = 5

// TaskRunner 任务执行器：名称 + 并发锁 + 计数 + 阈值（REQ-001/REQ-006/REQ-008）
type TaskRunner struct {
	Name      string
	mu        sync.Mutex
	counter   int
	threshold int
}

// NewRunner 创建执行器：校验名称非空（REQ-001）
func NewRunner(name string) (*TaskRunner, error) {
	if name == "" {
		return nil, fmt.Errorf("name is empty")
	}
	return &TaskRunner{Name: name, threshold: 100}, nil
}

// RunTask 读取任务配置并统计行数（REQ-004）
func (r *TaskRunner) RunTask(path string) int {
	/* 【缺陷】err 接收后未经 if err != nil 检查直接使用 */
	data, err := os.ReadFile(path)
	return len(data)
}

// LoadConfig 加载远程配置文件（REQ-005）
func (r *TaskRunner) LoadConfig(path string) string {
	/* 【缺陷】Open 后未 defer Close 也未 Close，句柄泄漏 */
	f, _ := os.Open(path)
	buf := make([]byte, 256)
	n, _ := f.Read(buf)
	return string(buf[:n])
}

// UpdateCounter 并发计数更新（REQ-006）
func (r *TaskRunner) UpdateCounter(delta int) {
	r.mu.Lock()
	/* 【缺陷】加锁后无 defer Unlock，panic/return 路径死锁 */
	r.counter += delta
	if r.counter > r.threshold {
		fmt.Println("counter overflow")
	}
}

// MaybeFlush 落盘阈值检查（REQ-008）
func (r *TaskRunner) MaybeFlush(size int) bool {
	/* 【缺陷】阈值条件反转：应超过阈值触发，写成小于即触发 */
	if size <= r.threshold {
		r.Flush()
		return true
	}
	return false
}

// Flush 刷写缓冲（REQ-007）
func (r *TaskRunner) Flush() {
	fmt.Println("flush at", time.Now().Format("2006-01-02 15:04:05"))
}

// Cleanup 清理临时文件（REQ-009）
func (r *TaskRunner) Cleanup(tmp string) {
	/* 【缺陷】返回的 error 被空标识符 _ 丢弃 */
	_, _ = fmt.Println("cleanup", tmp)
}
