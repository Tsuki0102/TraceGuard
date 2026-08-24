package com.traceguard.websocket;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SEC-10：WebSocket 一次性连接票据服务。
 *
 * 前端不再把 JWT 放到 WS URL 的 query 参数（会进入代理/访问日志），
 * 改为先调用 GET /ws/ticket 换取一次性票据（内存存储），WS 握手携带 ticket。
 * 票据特性：短过期（30 秒）、单次使用（consume 后即删除）、数量上限（防刷）。
 */
@Service
public class WSTicketService {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final int MAX_TICKETS = 200;

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * 为指定用户签发一次性票据。
     *
     * @return 票据字符串；超过数量上限或 userId 为空时返回 null
     */
    public String issue(Long userId) {
        cleanup();
        if (userId == null || tickets.size() >= MAX_TICKETS) {
            return null;
        }
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, new Ticket(userId, System.currentTimeMillis()));
        return ticket;
    }

    /**
     * 消费票据并返回绑定的 userId（单次使用，用后即删）。
     *
     * @return 有效则返回 userId，无效/过期/不存在返回 null
     */
    public Long consume(String ticket) {
        if (ticket == null) {
            return null;
        }
        Ticket t = tickets.remove(ticket);
        if (t == null) {
            return null;
        }
        if (System.currentTimeMillis() - t.issuedAt > TTL.toMillis()) {
            return null;
        }
        return t.userId;
    }

    /** 惰性清理过期票据 */
    private void cleanup() {
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(e -> now - e.getValue().issuedAt > TTL.toMillis());
    }

    private static final class Ticket {
        final Long userId;
        final long issuedAt;

        Ticket(Long userId, long issuedAt) {
            this.userId = userId;
            this.issuedAt = issuedAt;
        }
    }
}
