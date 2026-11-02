package com.wengu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// 不要 Spring Boot 默认生成的内存用户（启动时在日志里打印一个随机密码）：用户在 user 表里，
// 密码由 UserService 校验（决策 022）
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WenguApplication {

	public static void main(String[] args) {
		SpringApplication.run(WenguApplication.class, args);
	}

}
