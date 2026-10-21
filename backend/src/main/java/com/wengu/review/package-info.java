/**
 * 评分与调度：接收用户确认的评分，调用 FSRS 计算新的调度状态，维护 card_state 和 review_log。
 * <p>
 * 只通过 kp_id 工作，不关心知识点的内容。
 * 依赖：common。不依赖任何业务模块，绝不能依赖 knowledge。
 */
package com.wengu.review;
