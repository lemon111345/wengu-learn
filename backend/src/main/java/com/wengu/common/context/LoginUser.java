package com.wengu.common.context;

import java.io.Serializable;

/**
 * 登录后存进 Session 的用户信息。只有 id 和用户名，不含密码哈希：Session 存在内存里，没有必要多存一份。
 * <p>
 * 放在 common，这样各模块通过 {@link CurrentUser} 拿到用户 id 时不需要依赖 user 模块（决策 002）。
 * Session 中的对象要能序列化。
 */
public record LoginUser(long id, String username) implements Serializable {
}
