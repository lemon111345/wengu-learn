/**
 * FSRS 算法（纯 Java）：输入当前调度状态和评分，输出新状态。
 * <p>
 * 只放纯计算代码：不加 Spring 注解、不访问数据库、不依赖本包以外的业务代码。必须有单元测试。
 * <p>
 * 移植自 py-fsrs v6.3.2（https://github.com/open-spaced-repetition/py-fsrs），MIT License，
 * Copyright (c) 2022 Open Spaced Repetition。许可全文见仓库根目录 THIRD-PARTY-NOTICES.md，
 * 移植决策见 docs/decisions/003-FSRS移植.md。
 */
package com.wengu.review.fsrs;
