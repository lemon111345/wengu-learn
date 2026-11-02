package com.wengu.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** 用户，对应 user 表（V1）。user 是 MySQL 的关键字，表名加反引号。 */
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 唯一。按数据库默认的排序规则比较，不区分大小写 */
    private String username;
    /** 带算法前缀，如 {bcrypt}$2a$10$...。V1 插入的默认用户为空，登录不上（决策 017） */
    private String passwordHash;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
