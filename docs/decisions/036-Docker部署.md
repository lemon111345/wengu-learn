# 036 Docker 部署

## 背景

M1 的目标是“自部署闭环……Docker 一键启动”（#24）：在一台干净的机器上，一条 `docker compose up` 就能启动并完成一次学习。此前 `compose.yaml` 只有本地开发用的 MySQL，后端和前端都靠开发服务器运行。

前提：登录凭证是保存在后端内存中的 Session（决策 022），不需要 Redis；大模型配置只从环境变量读取（决策 004）；注册可以用 `WENGU_ALLOW_REGISTRATION` 关闭（决策 017）。

## 决策

### 1. 三个容器，开发和部署共用一个 compose.yaml

```
浏览器 → web（Nginx：前端静态文件，/api 转给后端）→ backend（Spring Boot :8080）→ mysql
         宿主机端口 WENGU_PORT（默认 8000）          不对外开放                    只绑定 127.0.0.1:WENGU_DB_PORT（默认 3307）
```

- 部署：`docker compose up -d --build`；本地开发只启动数据库：`docker compose up -d mysql`
- MySQL 只绑定本机：开发时后端从宿主机连 3307 照常可用，部署时外网连不进来，备份可以用
- 启动顺序靠健康检查：MySQL 能连上后才启动后端，后端 `/api/health` 正常后才启动 web
- 后端加 `host.docker.internal:host-gateway`：连宿主机上的 Ollama 时 Linux 也能用这个名字

### 2. 多阶段构建，编译在 Docker 里完成

- 后端：`eclipse-temurin:21.0.13_9-jdk-alpine-3.24` 中 `mvnw package`（跳过测试，测试要 Docker），jar 复制到 `eclipse-temurin:21.0.13_9-jre-alpine-3.24` 中，以非 root 用户运行
- 前端：`node:24.22.2-alpine` 中 `npm ci && npm run build`，`dist` 复制到 `nginx:1.30.6-alpine`
- 先复制依赖描述再复制代码，依赖下载单独一层；Maven 仓库和 npm 缓存放在 BuildKit 的缓存挂载中，重新构建不用重新下载，也不进最终镜像
- 基础镜像都固定到具体版本，不用 `latest`

### 3. Nginx 的配置

- `/api/` 转给后端时**关掉缓冲**、读超时 600 秒：学习对话是 SSE 流式输出，Nginx 默认缓冲会让回复攒到最后才出现；超时和后端的 `spring.mvc.async.request-timeout` 一致
- 请求体上限 10 MB：导入资料最多 10 万字
- 前端路由回落到 `index.html`；`/assets/` 带内容哈希，缓存一年；`index.html` 不缓存，升级后立刻生效

### 4. 配置写在 `.env`

- compose 用 `.env` 替换 `${...}`，后端容器通过 `env_file` 读取它（文件不存在也能启动）
- **`.env` 中没写的变量不传给后端**，由 `application.yml` 的默认值生效，默认值只在那一处维护；`.env.example` 中可选项都注释掉，并说明不要留等号后面为空的行（空值会覆盖默认值）
- 数据库地址和账号由 compose 指定，不从 `.env` 读
- `.gitignore` 的 `.env.*` 规则加例外 `!.env.example`

## 验证

从仓库重新 clone 到临时目录（Windows 上 clone，文件是 CRLF 行尾），按 README 复制 `.env.example` 为 `.env` 并填 Key，用独立的项目名和端口启动：

| 项目 | 结果 |
| --- | --- |
| 构建 | 第一次 268 秒（含下载）；有缓存时整套启动 44 秒 |
| CRLF 的 `.env` | 正确解析，Key 末尾没有回车；注释掉的选项没有传给后端 |
| Nginx | 刷新前端路由返回 `index.html`；静态文件缓存一年并 gzip；首页 no-cache；`/api` 转发正常 |
| 完整流程（DeepSeek） | 注册、识别题目、抽取、学习对话、评分建议都正常；流式输出逐段到达（最后一轮首字 1.1 秒、全部 3.1 秒） |
| 关闭注册 | `.env` 加 `WENGU_ALLOW_REGISTRATION=false` 后 `docker compose up -d` 只重建后端，注册接口返回关闭 |
| 连宿主机的 Ollama | `host.docker.internal:11434/v1` + qwen2.5:7b，识别题目成功（Docker Desktop on Windows） |
| 开发库 | 新的 compose 重建开发用的 MySQL 容器，数据卷不变，数据完整 |
| 备份与恢复 | README 的 mysqldump 命令备份后恢复到临时库，知识点数、迁移记录、中文内容一致 |

## 理由

- **共用一个 compose.yaml**：只有一份 MySQL 配置，不会改了一份忘了另一份；开发命令只多一个服务名
- **编译在 Docker 里**：干净的机器上没有 JDK 和 Node，这是“一条命令启动”的前提
- **前端和 Nginx 放在一个镜像**：前端只是静态文件，单独一个容器没有意义；浏览器只访问一个地址，没有跨域问题，Cookie 和 CSRF 的设置不用改
- **默认值只在 application.yml**：用 compose 的 `environment` 逐项传 `${VAR:-默认值}` 的话，每个默认值要写两遍

## 放弃的方案

| 方案 | 放弃原因 |
| --- | --- |
| 另建 compose.dev.yaml 给开发 | 两份 MySQL 配置要保持一致；开发命令要带 `-f` |
| 在宿主机上编译好，Docker 只复制产物 | 干净的机器上没有 JDK 和 Node |
| 默认端口 80 | Windows 上常被占用，非 root 的 Docker 也可能绑不了；8000 可以在 `.env` 中改 |
| 把 Ollama 放进 compose | 模型要用 GPU、体积大，多数人用云端模型；issue 中也明确不放 |
| Session 存进数据库（Spring Session JDBC） | 要新增依赖；M1 接受“后端重启后要重新登录” |

## 遗留问题

- 后端重建或重启后所有人要重新登录（Session 在内存中），README 已写明
- Linux 上连宿主机的 Ollama 需要 `OLLAMA_HOST=0.0.0.0`，没有实测
- 没有 HTTPS：对外提供服务时要在前面再加一层反向代理（M1 范围外）
