/**
 * 每日计划：根据用户时区和 day_start_hour 实时计算当天的“到期复习 + 新知识”，不建表。
 * <p>
 * 依赖：knowledge、review、user（只通过 user 的 Service 读取用户设置，不访问 user_settings 表）、common。
 * 任何模块都不得依赖本模块。
 */
package com.wengu.plan;
