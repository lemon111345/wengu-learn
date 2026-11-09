# 040 Session 存进 Redis

## 背景

登录状态存在后端内存中（决策 022），自用时每次重启后端都要重新登录，线上发版时所有用户都会掉线。决策 034 把“Session 存进 Redis”定为上线前必须完成，决策 038 又提前到 M2（#86）。

要定的是：用哪种存储方式、Redis 怎么部署、测试怎么不依赖外部 Redis。明确不做：非浏览器客户端的凭证（决策 034 第 3 节）、多实例部署、修改密码后其他设备的登录失效。

## 决策

### 1. 用 Spring Session，存储选默认的 `RedisSessionRepository`

- 新增依赖 `spring-boot-starter-session-data-redis`，版本由 Spring Boot 管理。业务代码不用改：`LoginSession` 用的是标准的 `HttpSession`
- 不用 indexed 模式：靠 Redis 的 TTL 过期，不需要开启键空间通知。“按用户查所有 Session”只有修改密码后让其他设备失效才用得上，这次不做
- Cookie 名从 `JSESSIONID` 变成 Spring Session 默认的 `SESSION`，仍然是 HttpOnly、SameSite=Lax；切换后要重新登录一次

### 2. 有效期要显式配置

`spring.session.timeout` 必须写在配置里，取 `server.servlet.session.timeout` 的值。**只配置后者对 Spring Session 不生效**：`RedisSessionTest` 实测 Redis 中的 TTL 只有 30 分钟，不是 30 天。

Cookie 设 `server.servlet.session.cookie.max-age: 400d`。不设的话是会话 Cookie，关闭浏览器后登录状态就丢了。Cookie 只在登录时写一次，之后的请求不会刷新，而 Redis 的 TTL 每次访问都会延长，所以 Max-Age 设成 Chrome 允许的上限 400 天，何时失效由 Redis 的“空闲 30 天”决定。Redis 中的 Session 过期后，浏览器里留下的 Cookie 就失效了，没有额外风险。代价是持续使用时，每 400 天要重新登录一次。

### 3. 读不出来的 Session 按未登录处理

仍然用默认的 JDK 序列化，加一层容错（`RedisSessionConfig`）。升级依赖后旧 Session 可能读不出来，默认会抛异常，带着旧 Cookie 的每个请求都是 500，用户自己解决不了。容错之后按未登录处理，重新登录即可。

### 4. Redis 的部署

- `compose.yaml` 新增 `redis:7-alpine`，后端等它就绪再启动
- 用数据卷 `redis-data` 保存：`docker compose down` 再启动后登录状态还在
- 映射到本机 6380 端口（`WENGU_REDIS_PORT`），只绑定 127.0.0.1：和 MySQL 用 3307 一样，避免和本机已有的 Redis 冲突
- **不设密码**：只绑定本机，容器之间走 Docker 内网，外网连不到，也省一项配置。M4 线上部署时再加
- Redis 成了必需的服务：它没启动时，登录和所有需要登录的接口都会失败

### 5. 测试不依赖外部 Redis

- 普通测试在 `src/test/resources/application.properties` 中关掉 Spring Session，登录状态留在 `MockHttpSession` 中，现有测试不用改
- `RedisSessionTest` 清空这一项，用 Testcontainers 启动真实的 Redis，验证存储、30 天 TTL、登录时更换 id、退出后删除、读不出来时按未登录处理
- `server.servlet.session.cookie.*` 在 MockMvc 下不作用到 Spring Session 的 Cookie（没有真实的 Web 服务器），Max-Age、HttpOnly、SameSite 要在真实后端上验证

## 理由

- **Spring Session**：Spring Security 的 Session 用法不用变，比自己写存储层简单，也是决策 022、034 定下的方向
- **Redis 而不是数据库**：决策 034 和 #86 定的是 Redis，M4 的限流和额度计数也要用它
- **JDK 序列化**：Session 中只有登录信息，改用 JSON 要给 Spring Security 的类型注册 Jackson 模块，代码更多，这里用不上；升级带来的风险由第 3 节兜底

## 放弃的方案

| 方案 | 放弃原因 |
| --- | --- |
| Spring Session JDBC | 不用多一个容器，但以后的限流和额度计数还是要 Redis，到时又要迁移一次 |
| JSON 序列化 | 要注册 Spring Security 的 Jackson 模块，还要给 `LoginUser` 写配置，收益不大 |
| indexed 模式 | 依赖键空间通知，有些托管 Redis 默认关闭；这次用不上索引 |
| 给 Redis 设默认密码 | 默认值人人知道，起不到保护作用，还多一项配置；只绑定本机更直接 |
| Cookie 不设 Max-Age | 关闭浏览器后要重新登录，Redis 里的 Session 还在也用不上 |
| Cookie 的 Max-Age 设 30 天 | Cookie 不随访问刷新，每天都在用的人也会在登录 30 天后掉线 |
| 每次请求刷新 Cookie | 要自己写过滤器，还要多写响应头；设成 400 天已经够用 |

## 遗留问题

- 线上部署时给 Redis 加密码（M4）
- Redis 没有开启 AOF，用默认的 RDB 快照：非正常退出可能丢失最近的登录状态，最多要重新登录一次，可以接受
