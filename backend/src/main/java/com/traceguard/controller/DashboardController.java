package com.traceguard.controller;

import com.traceguard.common.Result;
import com.traceguard.service.DashboardStatsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台聚合接口（W2 波：KPI 趋势 / 动态流 / 使用趋势 / 通知 / 全局搜索 / 系统状态 / 质量门槛）。
 * 全部需登录（LoginInterceptor），数据隔离在 DashboardStatsService 内按角色执行。
 */
@RestController
@RequestMapping("/dashboard")
@Api(tags = "11-工作台聚合")
public class DashboardController {

    @Autowired
    private DashboardStatsService statsService;

    @ApiOperation(value = "工作台 KPI 概览与周同比", notes = "项目数/已分析/平均覆盖率/累计缺陷 + 本周 vs 上周分析次数")
    @GetMapping("/overview")
    public Result overview() {
        return Result.success(statsService.overview());
    }

    @ApiOperation(value = "最近动态流", notes = "管理员看全部操作，普通用户只看自己；默认14条")
    @GetMapping("/activities")
    public Result activities(@RequestParam(defaultValue = "14") int limit) {
        return Result.success(statsService.activities(limit));
    }

    @ApiOperation(value = "使用趋势（按天）", notes = "days=7|30；分析任务数/完成数 + 登录次数")
    @GetMapping("/trend")
    public Result trend(@RequestParam(defaultValue = "7") int days) {
        return Result.success(statsService.trend(days));
    }

    @ApiOperation(value = "通知中心", notes = "待办提醒（项目状态派生）+ 最近分析任务结果")
    @GetMapping("/notifications")
    public Result notifications() {
        return Result.success(statsService.notifications());
    }

    @ApiOperation(value = "全局搜索", notes = "跨表搜索：项目名 / 需求 / 缺陷；数据隔离")
    @GetMapping("/search")
    public Result search(@RequestParam(required = false) String q) {
        return Result.success(statsService.search(q));
    }

    @ApiOperation(value = "系统运行状态", notes = "数据库连通 / 磁盘占用 / 任务队列 / WS 在线 / 最近备份")
    @GetMapping("/system-status")
    public Result systemStatus() {
        return Result.success(statsService.systemStatus());
    }

    @ApiOperation(value = "质量门槛配置", notes = "sys_config.quality_gate（percent 制），无配置返回默认阈值")
    @GetMapping("/gate")
    public Result gate() {
        return Result.success(statsService.gateConfig());
    }

    @ApiOperation(value = "菜单配置", notes = "sys_config.menu_config，缺省返回空 = 全部菜单可见")
    @GetMapping("/menu-config")
    public Result menuConfig() {
        return Result.success(statsService.menuConfig());
    }
}