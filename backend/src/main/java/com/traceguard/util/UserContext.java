package com.traceguard.util;

/**
 * 当前登录用户上下文（基于ThreadLocal）
 */
public class UserContext {

    private static final ThreadLocal<Long> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> usernameHolder = new ThreadLocal<>();
    private static final ThreadLocal<String> roleHolder = new ThreadLocal<>();

    public static void set(Long userId, String username, String role) {
        userIdHolder.set(userId);
        usernameHolder.set(username);
        roleHolder.set(role);
    }

    public static Long getUserId() {
        return userIdHolder.get();
    }

    public static String getUsername() {
        return usernameHolder.get();
    }

    public static String getRole() {
        return roleHolder.get();
    }

    public static boolean isAdmin() {
        return "admin".equals(roleHolder.get());
    }

    public static void clear() {
        userIdHolder.remove();
        usernameHolder.remove();
        roleHolder.remove();
    }
}
