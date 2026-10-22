# 003 FSRS 移植

## 背景

调度算法是“每天学什么、什么时候复习”的核心。技术方案确定了用 FSRS，并自行移植为 Java、用 Python 版的结果做单元测试校验（issue #1）。移植前需要确定参照哪个版本、移植哪些部分、状态模型怎么和数据模型对应，以及怎样证明 Java 版和 Python 版结果一致。

## 决策

### 1. 参照 py-fsrs v6.3.2，算法为 FSRS-6

- 版本：py-fsrs 6.3.2（2026-08-09 发布，移植时的最新稳定版），21 个模型参数
- 协议：MIT。移植代码头部注明出处，许可全文放在根目录 `THIRD-PARTY-NOTICES.md`

### 2. 移植范围

| 移植 | 不移植 |
| --- | --- |
| Rating、State、Card、Scheduler 的 review（复习一次）和 retrievability（保持率）、参数校验、fuzz | optimizer（根据个人历史训练参数，依赖 torch）、reschedule_card（参数变化后按历史重算）、ReviewLog、JSON 序列化 |

不移植的部分 M0 用不到；持久化归 review 模块，fsrs 包只做计算。

### 3. 状态模型与 py-fsrs 一致

- 只有 LEARNING / REVIEW / RELEARNING 三种状态，**没有单独的“新”状态**
- **新卡 = 从未复习过（last_review 为空）**
- Card 是不可变 record，不带 card_id，review 返回新卡片

### 4. 项目默认学习步、重学步都为空，开启 fuzz

- 学习步、重学步可配置，`Scheduler.projectDefault()` 中都为空
- 学习步为空：新卡复习一次就进入 REVIEW，即使评“忘了”也至少隔一天
- 重学步为空：复习时“忘了”也留在 REVIEW，降低稳定性、重新计算间隔，至少隔一天
- 开启 fuzz，把同一天学的知识点的到期日稍微错开

### 5. 与 Python 版对齐的细节

| 细节 | 做法 | 是否影响结果 |
| --- | --- | --- |
| Python `round()` 是四舍六入五成双 | 用 `Math.rint` | **影响**：原始间隔或 fuzz 结果恰为 x.5 时，`Math.round` 会多一天 |
| `timedelta.days` 向下取整 | 用 `Math.floorDiv` | 当前用法下与 `Duration.toDays()` 等价（只用于“< 1”和“max(0, …)”判断），保持一致防止以后踩坑 |
| 原代码写 `math.e ** x` | 照写 `Math.pow(Math.E, x)` | 实测换成 `Math.exp(x)` 在 1e-9 内无差别 |
| fuzz 区间上下界的 `round()` | 用 `Math.rint` | 等价：上下界不可能恰为 x.5 |

### 6. 用 py-fsrs 生成的用例做比对测试

- 生成脚本 `tools/fsrs-testgen/generate.py` 用 PEP 723 内联依赖固定 `fsrs==6.3.2`，`uv run` 即可复现；固定随机种子，重复生成结果逐字节相同
- 12 种配置（含 py-fsrs 默认学习步与本项目的空学习步）× 人工场景 + 随机复习序列，共 240 个用例、2665 次复习
- 每一步沿用 Java 自己上一步的结果，误差会累积；`state`、`step`、`due`、`last_review` 必须完全相同，`stability`、`difficulty`、保持率允许相对误差 1e-9
- fuzz 回放：生成时替换 `fsrs.scheduler.random`，记录每次复习用到的随机数；Java 端 `Scheduler` 通过构造参数注入 `DoubleSupplier`，测试传入回放器，多取或少取都失败，因此同时验证了调用随机数的时机
- 正式运行时随机数来自 `ThreadLocalRandom`，Scheduler 实例可以跨线程共享

### 7. 用变异测试检验测试本身

故意改错代码，确认测试能失败。第一版用例漏掉了两类错误，已补充：

- 取整方式（`rint` → `round`）：间隔几乎不会恰为 x.5。新增 `half_intervals` 配置（初始稳定性设为 1.5、2.5、3.5、4.5，目标保持率 0.9 时首次间隔恰为 x.5），并补充 fuzz 结果为 8.5 的单元测试
- fuzz 作用范围（误对学习步、重学步做 fuzz）：原有步长都是分钟级，进入 fuzz 也会原样返回。新增 `long_steps_fuzz` 配置（学习步 1 天、3 天，重学步 3 天）

### 8. 数据模型随之调整

- card_state：增加 `step`；`state` 取 FSRS 的三种状态；**删除 `reps`、`lapses`**——它们能从 review_log 统计出来，按“存事实，算派生”不重复存储
- review_log：增加 `is_first_review`。新卡的 state 也是 LEARNING，`state_before` 无法区分“第一次学”和“学习步中”
- 每日计划：到期复习的条件是“last_review 不为空，且 due 早于今天结束”，否则新卡会被误当成到期复习；今天已学的新知识数改用 `is_first_review` 统计

## 理由

- **固定版本**：FSRS 算法和默认参数会随版本变化，固定版本才能让测试结果稳定、可复现
- **状态模型跟随 py-fsrs**：只有模型一致，才能逐条比对；“新卡”用 last_review 为空表示，比多一个状态更简单，也不会出现“状态是新但已有稳定性”的矛盾数据
- **默认没有学习步**：学习步是为背单词这类几秒钟一张的卡片设计的；本项目每个知识点是一段几分钟的 AI 对话，同一天重复出现体验差，也浪费模型调用
- **随机数可注入**：Java 和 Python 的随机数序列不同，无法用同一个种子比对；注入后算法变成确定的纯函数，可以完全复现 Python 的结果
- **变异测试**：用例全部通过只能说明“没发现不一致”，不能说明“能发现不一致”；变异测试证明了测试有效，也发现了盲区

## 放弃的方案

| 方案 | 放弃原因 |
| --- | --- |
| 直接引入现成的 Java 移植库（如 java-fsrs） | 新增第三方依赖；版本跟进不受控；自己移植一遍才能读懂算法 |
| 按 py-fsrs 原样分别写 Learning、Relearning 两个分支 | 两段代码逐行重复，只差步长列表；合并为一个方法后不会改一处漏一处 |
| 增加 NEW 状态 | 与 py-fsrs 不一致，无法直接比对；新卡用 last_review 为空即可表示 |
| 保留 py-fsrs 默认学习步（1 分钟、10 分钟） | 同一知识点当天会出现多次，见上文理由 |
| fuzz 测试只检查结果落在区间内 | 只能证明“看起来合理”，不能证明和 Python 一致；回放随机数可以逐条比对 |
| 随机数用 `RandomGenerator.getDefault()` | 返回的生成器不是线程安全的，Scheduler 会被多个请求共享 |
| 生成全部缩进的 JSON | 1MB 以上；改为每次复习一行，可读且体积小 |
