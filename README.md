# 温故（wengu-learn）

把“AI 追问式学习”和“间隔重复编排”结合的开源学习平台：导入面试题、源码和文档，系统每天安排少量新知识和到期复习，通过 AI 对话学懂，再由 FSRS 算法安排下次复习。

> 项目处于早期开发阶段（M1 自部署闭环）。需求见 [docs/prd.md](docs/prd.md)，技术方案见 [docs/tech-design.md](docs/tech-design.md)。

## 本地开发

需要 JDK 21 和 Docker。Maven 不用单独安装，使用项目自带的 Maven Wrapper：

```powershell
docker compose up -d         # 在仓库根目录启动本地开发用的 MySQL 8.4（宿主机端口 3307）
cd backend
.\mvnw.cmd test              # 运行测试（测试会用 Testcontainers 另起一个 MySQL，需要 Docker 在运行）
.\mvnw.cmd spring-boot:run   # 启动后端，健康检查：http://localhost:8080/api/health
```

后端启动时由 Flyway 自动建表（`backend/src/main/resources/db/migration`）。数据库连接默认对应 `compose.yaml`，需要时用环境变量覆盖：

| 环境变量 | 说明 | 默认值 |
| --- | --- | --- |
| `WENGU_DB_URL` | JDBC 地址，需带 `connectionTimeZone=UTC` | `jdbc:mysql://localhost:3307/wengu?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` |
| `WENGU_DB_USERNAME` / `WENGU_DB_PASSWORD` | 数据库账号 | `wengu` / `wengu` |
| `WENGU_TIMEZONE` | 默认用户的时区，只在第一次建库时写入 | `Asia/Shanghai` |

### 配置大模型

后端通过兼容 OpenAI 格式的接口调用大模型，配置**只从环境变量读取**，不写进任何配置文件：

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
| 本地 Ollama | `http://localhost:11434/v1` | 已拉取的模型，如 `qwen3:8b` | 不设（空值表示不发送认证头） |

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

## 许可

[GNU AGPL-3.0](LICENSE)。可以自由使用、修改和自部署；如果修改后通过网络对外提供服务，需要按同样的许可公开修改后的完整源码。

本项目包含移植自第三方开源项目的代码（MIT 许可），见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
