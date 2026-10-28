package com.wengu.common.context;

import org.springframework.stereotype.Component;

/**
 * 当前用户。各模块通过它拿到 user_id，不为此依赖 user 模块（见决策 002）。
 * <p>
 * M1 只有单用户模式，固定返回迁移脚本 V1 插入的默认用户。M3 做多用户模式时，改为从登录信息中读取，
 * 调用方不用改。
 */
@Component
public class CurrentUser {

    /** V1__init.sql 插入的默认用户。 */
    public static final long DEFAULT_USER_ID = 1L;

    public long id() {
        return DEFAULT_USER_ID;
    }
}
