# 温故（wengu-learn）

把“AI 追问式学习”和“间隔重复编排”结合的开源学习平台：导入面试题、源码和文档，系统每天安排少量新知识和到期复习，通过 AI 对话学懂，再由 FSRS 算法安排下次复习。

> 项目处于早期开发阶段（M1 自部署闭环）。需求见 [docs/prd.md](docs/prd.md)，技术方案见 [docs/tech-design.md](docs/tech-design.md)。

## 部署（Docker Compose）

只需要 Docker（Windows、macOS 用 Docker Desktop），不用安装 JDK 和 Node，编译都在 Docker 里完成。

```bash
git clone https://github.com/lemon111345/wengu-learn.git
cd wengu-learn
cp .env.example .env          # Windows PowerShell：Copy-Item .env.example .env
# 编辑 .env，填写 WENGU_LLM_API_KEY；其他选项见文件中的说明
docker compose up -d --build
```

第一次启动要下载基础镜像和依赖、编译后端和前端，需要几分钟。完成后打开 http://localhost:8000 （端口可在 `.env` 中用 `WENGU_PORT` 修改）。

启动了四个容器：

| 容器 | 作用 |
| --- | --- |
| `web` | Nginx：提供前端页面，并把 `/api` 转给后端 |
| `backend` | 后端，不直接对外开放 |
| `mysql` | 数据库，数据保存在 Docker 卷 `mysql-data` 中；只对本机开放端口 3307（`WENGU_DB_PORT`），供备份使用 |
| `redis` | 保存登录状态，数据保存在 Docker 卷 `redis-data` 中；只对本机开放端口 6380（`WENGU_REDIS_PORT`） |

**第一次使用：**先注册自己的账号。不打算让别人注册的话，在 `.env` 中加上 `WENGU_ALLOW_REGISTRATION=false`，再执行一次 `docker compose up -d`（只会重建后端）。

**修改配置：**改完 `.env` 后执行 `docker compose up -d`。后端重建或重启后不用重新登录：登录状态保存在 Redis 中。

**升级：**

```bash
git pull
docker compose up -d --build
```

数据库结构由后端启动时自动升级（Flyway）。升级前建议先备份。

**备份与恢复：**

```bash
# 备份（密码是 .env 中的 WENGU_DB_PASSWORD，默认 wengu）
docker compose exec -T mysql mysqldump -uwengu -pwengu --single-transaction wengu > wengu-backup.sql
# 恢复
docker compose exec -T mysql mysql -uwengu -pwengu wengu < wengu-backup.sql
```

**停止：**`docker compose down`。数据保留在卷中；加上 `-v` 会连数据一起删除。

### 连接 Ollama

Ollama 运行在宿主机上（不放进 compose）。在 `.env` 中写：

```
WENGU_LLM_BASE_URL=http://host.docker.internal:11434/v1
WENGU_LLM_MODEL=qwen2.5:7b
```

- Ollama 的上下文长度必须设到至少 16384，见下面“配置大模型”中的说明
- Windows、macOS（Docker Desktop）上实测可以直接连上
- Linux 上 Ollama 默认只监听 127.0.0.1，容器连不到，需要在 Ollama 的服务配置中设置 `OLLAMA_HOST=0.0.0.0` 后重启 Ollama（这会让局域网也能访问 Ollama，注意防火墙）。这一点还没有实测

## 本地开发

需要 JDK 21 和 Docker。Maven 不用单独安装，使用项目自带的 Maven Wrapper：

```powershell
docker compose up -d mysql redis   # 在仓库根目录只启动 MySQL 8.4（宿主机端口 3307）和 Redis（宿主机端口 6380）；不带服务名会把整个应用都启动起来
cd backend
.\mvnw.cmd test              # 运行测试（测试会用 Testcontainers 另起 MySQL 和 Redis，需要 Docker 在运行）
.\mvnw.cmd spring-boot:run   # 启动后端，健康检查：http://localhost:8080/api/health（不需要登录）
```

后端启动时由 Flyway 自动建表（`backend/src/main/resources/db/migration`）。数据库连接默认对应 `compose.yaml`，需要时用环境变量覆盖：

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `WENGU_DB_URL` | JDBC 地址，需带 `connectionTimeZone=UTC` | `jdbc:mysql://localhost:3307/wengu?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| `WENGU_DB_USERNAME` / `WENGU_DB_PASSWORD` | 数据库账号 | `wengu` / `wengu` |
| `WENGU_REDIS_HOST` / `WENGU_REDIS_PORT` | Redis 地址，保存登录状态 | `localhost` / `6380` |
| `WENGU_TIMEZONE` | 注册时读不到浏览器时区（或不合法）时使用的时区，以后可以在设置中修改 | `Asia/Shanghai` |
| `WENGU_ALLOW_REGISTRATION` | 是否开放注册 | `true` |

### 前端

需要 Node.js 20.19 以上（或 22.12 以上）。先按上面启动后端，再：

```powershell
cd frontend
npm install
npm run dev     # 开发服务器 http://localhost:5173，/api 开头的请求转发给后端
npm run build   # 类型检查并打包到 frontend/dist
```

后端默认在 `http://localhost:8080`。改了端口的话，在 `frontend/.env.local` 中写 `WENGU_API_TARGET=http://localhost:18080`（这个文件不提交）。

### 账号

所有人都要注册登录后使用，没有管理员。自己部署时：

1. 保持 `WENGU_ALLOW_REGISTRATION` 为默认的 `true`，启动后注册自己的账号
2. 不打算让别人注册的话，把它设为 `false` 后重启。已有的账号照常登录，注册页显示“暂未开放注册”

登录状态保存在后端的内存中，30 天不使用才会失效；**后端重启后需要重新登录**。

忘记密码目前只能直接改数据库：把 `user` 表中这个账号的 `password_hash` 换成新密码的 bcrypt 哈希（带 `{bcrypt}` 前缀）。

### 从只有默认用户的旧版本升级

#46 之前的版本没有注册登录，所有数据都属于迁移脚本插入的默认用户（id 为 1，用户名 `default`）。升级时：

- 默认用户没有数据：迁移脚本会自动删掉它，不用处理
- 默认用户有数据：迁移脚本不动它。注册自己的账号后，用 [`tools/migrate-default-user.sql`](tools/migrate-default-user.sql) 把数据转过去，用法见脚本开头的说明。**执行前先备份数据库**

### 配置大模型

后端通过兼容 OpenAI 格式的接口调用大模型，配置**只从环境变量读取**，不写进仓库中的任何配置文件（用 Docker Compose 部署时写在不提交的 `.env` 中）：

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `WENGU_LLM_API_KEY` | API Key | 空（应用照常启动，调用模型时才失败） |
| `WENGU_LLM_BASE_URL` | 接口地址 | `https://api.deepseek.com` |
| `WENGU_LLM_MODEL` | 模型名 | `deepseek-flash` |
| `WENGU_LLM_TIMEOUT` | 单次请求超时，如 `300s` | `180s` |

> **注意 base-url 的写法**：地址会原样使用，程序只在后面拼接 `/chat/completions`，不会自动补 `/v1`。服务商的地址本身带 `/v1` 的，要写全。

常见配置示例：

| 服务 | `WENGU_LLM_BASE_URL` | `WENGU_LLM_MODEL` | `WENGU_LLM_API_KEY` |
| --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com`（默认，可不设） | `deepseek-flash`（默认）或 `deepseek-v4-pro` | 必填 |
| OpenAI | `https://api.openai.com/v1` | 如 `gpt-4o-mini` | 必填 |
| 本地 Ollama | `http://localhost:11434/v1` | 已拉取的模型，如 `qwen2.5:7b`（实测过） | 不设（空值表示不发送认证头） |

> **关于本地 Ollama**：可用于自部署和离线试用，效果明显弱于云端模型，不作为推荐配置（实测 qwen2.5:7b：学习对话中会替学生讲答案，评分偏宽松）。
>
> 使用时**必须把 Ollama 的上下文长度设到至少 16384**。Ollama 默认按显存决定上下文长度，常常只有 4096；超出的提示词会被悄悄截断，抽取知识点会失败，提示“输出不符合要求”。设置方法：在运行 Ollama 的机器上设环境变量 `OLLAMA_CONTEXT_LENGTH=16384`，然后重启 Ollama。温故走的是 OpenAI 兼容接口，不能在请求里指定上下文长度。详见[决策 032](docs/decisions/032-大模型错误提示与模型状态.md) 第 6 节。

设置方式（Windows PowerShell）：

```powershell
# 永久保存到用户环境变量，之后新开的终端都能读到（设置后需重启终端或 IDE）
[Environment]::SetEnvironmentVariable('WENGU_LLM_API_KEY', '你的 Key', 'User')

# 或只在当前终端临时生效
$env:WENGU_LLM_API_KEY = '你的 Key'
```

macOS / Linux：`export WENGU_LLM_API_KEY='你的 Key'`。

### 验证大模型连接

实连测试会真实调用模型、产生少量费用，默认跳过，需要显式开启：

```powershell
cd backend
.\mvnw.cmd test "-Dtest=LlmServiceLiveTest" "-Dllm.live=true"
```

会分别验证一次普通调用和一次流式调用（流式要求收到多段内容）。

学习对话也有一个实连测试，用真实模型走一遍开场、跳过讲解、完整作答、收尾，再接着问一个问题，检查模型是否按约定输出【完整作答】和【学习结束】，并打印接着问的回答供人工查看（需要 Docker，会启动测试用的 MySQL）：

```powershell
.\mvnw.cmd test "-Dtest=ChatLiveTest" "-Dllm.live=true"
```

## 参与开发

项目规则（模块依赖边界、时间一律存 UTC、用户数据表带 `user_id`、接口与后端约定、当前阶段不做的功能等）写在 [CLAUDE.md](CLAUDE.md)。Claude Code 会自动读取它；不用 Claude Code 的话，也请按其中的规则开发。

个人的工作偏好可以写进仓库根目录的 `CLAUDE.local.md`，它和 CLAUDE.md 一起加载，已被 `.gitignore` 忽略。

## 许可

[GNU AGPL-3.0](LICENSE)。可以自由使用、修改和自部署；如果修改后通过网络对外提供服务，需要按同样的许可公开修改后的完整源码。

本项目包含移植自第三方开源项目的代码（MIT 许可），见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
