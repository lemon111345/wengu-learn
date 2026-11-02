package com.wengu.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wengu.common.context.LoginUser;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.user.dto.ChangePasswordRequest;
import com.wengu.user.dto.LoginRequest;
import com.wengu.user.dto.RegisterRequest;
import com.wengu.user.entity.User;
import com.wengu.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 注册、校验密码、修改密码（决策 017、022）。登录状态存进 Session 由 Controller 调用 common 的 LoginSession 完成，
 * 这里只回答“这个人是谁”。
 * <p>
 * 密码和它的哈希都不写进日志。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 头像取用户名的首字（ui.md 4.1），所以允许中文。\p{IsHan} 是汉字 */
    private static final Pattern USERNAME = Pattern.compile("[\\p{IsHan}A-Za-z0-9_-]{2,32}");

    static final int PASSWORD_MIN_LENGTH = 8;
    static final int PASSWORD_MAX_LENGTH = 64;
    /** bcrypt 只取前 72 个字节，超出时 Spring Security 直接报错。一个汉字占 3 个字节，所以字符数之外还要限制字节数 */
    private static final int PASSWORD_MAX_BYTES = 72;

    private final UserMapper userMapper;
    private final UserSettingsService settingsService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final boolean registrationOpen;
    /**
     * 用户名不存在时也拿它比对一次，让“用户不存在”和“密码错误”花的时间一样，
     * 不让人通过响应快慢试探哪些用户名已经注册。
     */
    private final String dummyHash;

    public UserService(UserMapper userMapper, UserSettingsService settingsService, PasswordEncoder passwordEncoder,
                       TransactionTemplate transactionTemplate, Clock clock,
                       @Value("${wengu.auth.allow-registration}") boolean registrationOpen) {
        this.userMapper = userMapper;
        this.settingsService = settingsService;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.registrationOpen = registrationOpen;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    public boolean registrationOpen() {
        return registrationOpen;
    }

    /**
     * 注册，同时写入默认设置。用户和设置在一个事务中，不会出现有用户没设置的情况。
     *
     * @throws BusinessException 注册已关闭时为 REGISTRATION_CLOSED；用户名或密码不合规时为 BAD_REQUEST；
     *                           用户名已被使用时为 USERNAME_TAKEN
     */
    public LoginUser register(RegisterRequest request) {
        if (!registrationOpen) {
            throw new BusinessException(ErrorCode.REGISTRATION_CLOSED);
        }
        String username = validUsername(request.username());
        String password = validPassword(request.password());

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setCreatedAt(Instant.now(clock));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                userMapper.insert(user);
                settingsService.createDefaults(user.getId(), request.timezone());
            });
        } catch (DuplicateKeyException e) {
            // 先查再插也挡不住两个人同时注册同一个名字，以唯一索引为准
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        log.info("新用户注册: id={}, username={}", user.getId(), username);
        return new LoginUser(user.getId(), username);
    }

    /**
     * 校验用户名和密码。
     *
     * @throws BusinessException 用户不存在、密码错误、或者是没有密码的默认用户时，都为 LOGIN_FAILED
     */
    public LoginUser authenticate(LoginRequest request) {
        String username = request.username() == null ? "" : request.username().strip();
        String password = request.password() == null ? "" : request.password();
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        String hash = user == null || user.getPasswordHash() == null ? dummyHash : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(password, hash);
        if (user == null || user.getPasswordHash() == null || !matches) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        return new LoginUser(user.getId(), user.getUsername());
    }

    /**
     * 修改密码。当前设备的登录保留；其他设备上的登录不会失效，M1 不处理（决策 022）。
     *
     * @throws BusinessException 旧密码不对、新密码不合规时为 BAD_REQUEST。旧密码不对不用 401：
     *                           前端收到 401 会当作没登录，跳到登录页
     */
    public void changePassword(long userId, ChangePasswordRequest request) {
        User user = userMapper.selectById(userId);
        String oldPassword = request.oldPassword() == null ? "" : request.oldPassword();
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前密码不正确");
        }
        String newPassword = validPassword(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        log.info("用户修改了密码: id={}", userId);
    }

    private static String validUsername(String username) {
        String stripped = username == null ? "" : username.strip();
        if (!USERNAME.matcher(stripped).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名为 2～32 个字符，可以用中文、字母、数字、下划线和连字符");
        }
        return stripped;
    }

    /** 密码不去掉首尾空格：空格也可以是密码的一部分。 */
    private static String validPassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码至少 " + PASSWORD_MIN_LENGTH + " 位");
        }
        if (password.length() > PASSWORD_MAX_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "密码太长了：最多 " + PASSWORD_MAX_LENGTH + " 个字符，含中文时上限更低");
        }
        return password;
    }
}
