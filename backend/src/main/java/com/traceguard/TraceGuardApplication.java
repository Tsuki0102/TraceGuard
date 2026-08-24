package com.traceguard;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.traceguard.mapper")
@EnableCaching
@EnableScheduling
public class TraceGuardApplication {

    private final com.traceguard.service.LlmService llmService;

    public TraceGuardApplication(com.traceguard.service.LlmService llmService) {
        this.llmService = llmService;
    }

    public static void main(String[] args) {
        SpringApplication.run(TraceGuardApplication.class, args);
    }

    /** GAP-021：启动就绪后以 tg_llm_config 表配置覆盖 yml（api_key 解密注入） */
    @EventListener(ApplicationReadyEvent.class)
    public void applyLlmDbConfig() {
        try {
            llmService.applyDbConfigIfPresent();
        } catch (Exception e) {
            // 启动不因 LLM 配置问题失败
        }
    }
}
