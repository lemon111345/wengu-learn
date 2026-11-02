/**
 * 公共基础：统一返回格式、全局异常处理、获取当前用户 id、工具类。
 * <p>
 * 登录与鉴权（SecurityConfig、LoginSession）和获取当前用户 id（CurrentUser）也在这里：它们不读用户表，
 * 不依赖 user 模块（决策 022）。
 * <p>
 * 不依赖任何业务模块，所有模块都可以依赖它。
 */
package com.wengu.common;
