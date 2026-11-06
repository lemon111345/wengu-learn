# 温故（wengu-learn）

把“AI 追问式学习”和“间隔重复编排”结合的开源学习平台。用户导入面试题、源码和文档，系统每天安排少量新知识和到期复习，通过 AI 对话学懂，再由 FSRS 算法安排下次复习。

完整背景见下面两份文档（不会自动加载，需要时再读）：
- 需求：[docs/prd.md](docs/prd.md)
- 技术方案：[docs/tech-design.md](docs/tech-design.md)

## 技术栈

- 后端：Spring Boot 4、Java 21、Maven、MyBatis-Plus、MySQL（Flyway 管理表结构，测试用 Testcontainers）、Redis、Spring Security（始终启用，只有多用户模式，见决策 017）、Spring AI 2.x
- 文档解析：Apache PDFBox、Apache POI
- 前端：Vue 3 + TypeScript + Vite + Element Plus
- 部署：Docker Compose + Nginx

## 包结构

按业务分包，每个模块内部分 controller / service / mapper / entity / dto：

```
com.wengu
├── source      资料导入与解析
├── knowledge   知识点
├── plan        每日计划
├── review      评分与调度
│   └── fsrs    FSRS 算法（纯 Java）
├── chat        学习会话与对话
├── llm         大模型接入、降级链
├── user        用户与设置
└── common      统一返回、全局异常、工具类
```

## 必须遵守的规则

- **模块之间只能调用对方的 Service**，不能直接使用对方的 Mapper 或访问对方的表。
- **依赖方向单向，不允许循环依赖**。完整依赖关系（见 `docs/decisions/002-模块依赖边界.md`）：
  - plan → knowledge、review、user、chat（今天的学习时长，决策 025）
  - chat → knowledge、llm、review、user（评分时换算学习日，决策 026）
  - knowledge → source、llm、review、user（知识库显示学习日，决策 030）
  - source → llm（切分题目要调用模型，见决策 011）
  - review、llm、user 不依赖其他业务模块；所有模块都可以依赖 common
  - review 只通过 kp_id 工作，绝不能依赖 knowledge
  - model_config 归 llm 管理；当前用户 id 通过 common 获取，不为此依赖 user
- **review.fsrs 只放纯计算代码**：不加 Spring 注解、不访问数据库。输入当前状态和评分，输出新状态。必须有单元测试。
- **用户数据表都带 user_id**；官方内容（图谱、系统级技能、知识点模板，决策 033）是全局数据，不带 user_id。系统级与用户级混在一张表的数据（如技能），查询时必须限定为“系统级，或属于当前用户”。
- **时间一律存 UTC**；“今天”的判断根据用户的时区和 day_start_hour 计算。
- **每日计划不建表**，实时计算，逻辑见技术方案。
- **前端实现以 `docs/design/ui.md` 为准**。截图与规范不一致时以规范为准；`docs/design/mockups/` 只用来查数值，不照抄代码。
- 接口统一返回格式，异常由全局异常处理器统一处理。
- **API Key 不得出现在日志中**，返回给前端时只显示末四位。
- **私人数据放在 `data-private/` 目录**（如来自付费平台的题库），该目录必须写进 .gitignore，绝不提交到仓库。示例数据只使用自写或明确允许分享的内容。

### 接口与后端约定（详见技术方案同名一节、决策 034）

- **接口只做向后兼容的修改**：可以新增字段，不删除、不重命名已有字段；确需破坏性修改时新增接口版本，旧版本保留一段时间（小程序旧版本会长期存在）。
- **Service 只接收 userId，不读取 Session**。
- **日期由后端换算成学习日后返回**，客户端不自行计算时区。
- **计划、排序、掌握程度等业务规则全部放在后端**，客户端只负责显示。
- **新接口不依赖只有浏览器才有的机制**（重定向、Referer 等）。
- **新设计的耗时 AI 接口优先采用异步方式**（提交后返回任务 id，客户端轮询结果）。
- **微信登录等仅线上版需要的功能，通过配置开关启用**，自部署默认关闭。

## PR 约定

- **只改文档的 PR 可以不建 issue**，但要在 PR 描述中写明原因。其他工作（包括骨架类工作）先建 issue。
- **chat、review、plan 模块的 PR，描述中要有“关键取舍”一节**：写明选了什么、放弃了什么，方便作者复述。

## 当前阶段不做

由用户手动创建和维护的标签、分类系统（知识点的技能归属由系统图谱和 AI 决定，用户只确认，不配置，见决策 033）、手动番茄钟、产品内用户之间的题库分享、移动端（以后的小程序见决策 034）、MCP 集成、多人协作、RAG 向量检索。如果某个实现似乎需要这些，先停下来问我。
