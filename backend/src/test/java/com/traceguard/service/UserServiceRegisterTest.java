package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.entity.User;
import com.traceguard.mapper.UserMapper;
import com.traceguard.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册安全测试（SRS 5.2.3）：公开注册不得允许客户端指定角色提权
 */
class UserServiceRegisterTest {

    private UserService userService;
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        userService = new UserService();
        userMapper = Mockito.mock(UserMapper.class);
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "jwtUtil", Mockito.mock(JwtUtil.class));
        ReflectionTestUtils.setField(userService, "loginAttemptService", Mockito.mock(LoginAttemptService.class));
    }

    @Test
    @DisplayName("客户端传入 role=admin 注册时被强制降为普通用户")
    void registerShouldForceUserRoleEvenIfAdminProvided() {
        Mockito.when(userMapper.selectCount(Mockito.any(LambdaQueryWrapper.class))).thenReturn(0L);
        User input = new User();
        input.setUsername("escalator");
        input.setPassword("Abc12345");
        input.setRole("admin");

        User result = userService.register(input);

        assertThat(result.getRole()).isEqualTo("user");
    }

    @Test
    @DisplayName("未传角色时默认普通用户")
    void registerShouldDefaultToUserWhenRoleMissing() {
        Mockito.when(userMapper.selectCount(Mockito.any(LambdaQueryWrapper.class))).thenReturn(0L);
        User input = new User();
        input.setUsername("normal");
        input.setPassword("Abc12345");

        User result = userService.register(input);

        assertThat(result.getRole()).isEqualTo("user");
    }

    @Test
    @DisplayName("注册返回不含密码且入库前已BCrypt加密")
    void registerShouldEncryptPasswordAndNotLeakIt() {
        Mockito.when(userMapper.selectCount(Mockito.any(LambdaQueryWrapper.class))).thenReturn(0L);
        // insert 与断言共享同一对象引用，须在 insert 调用时机快照入库密码
        java.util.concurrent.atomic.AtomicReference<String> storedPwd = new java.util.concurrent.atomic.AtomicReference<>();
        Mockito.doAnswer(inv -> {
            storedPwd.set(((User) inv.getArgument(0)).getPassword());
            return 1;
        }).when(userMapper).insert(Mockito.any(User.class));
        User input = new User();
        input.setUsername("safeuser");
        input.setPassword("Abc12345");

        User result = userService.register(input);

        assertThat(result.getPassword()).isNull();
        // 入库密码应为 BCrypt 格式（$2a$ 开头）
        assertThat(storedPwd.get()).startsWith("$2a$");
    }

    @Test
    @DisplayName("AUD-03：注册时记录密码修改时间 passwordUpdatedAt 不为空")
    void registerShouldSetPasswordUpdatedAt() {
        Mockito.when(userMapper.selectCount(Mockito.any(LambdaQueryWrapper.class))).thenReturn(0L);
        User input = new User();
        input.setUsername("newbie");
        input.setPassword("Abc12345");

        User result = userService.register(input);

        assertThat(result.getPasswordUpdatedAt()).isNotNull();
    }
}
