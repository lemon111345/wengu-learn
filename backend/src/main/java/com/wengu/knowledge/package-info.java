/**
 * 知识点：从资料中抽取出的知识点及其与资料的关联，支持预览、删除、合并、编辑。
 * <p>
 * 依赖：source（读取资料内容）、llm（AI 抽取知识点）、review（知识点创建后初始化调度状态）、common。
 * 不得依赖 plan、chat。
 */
package com.wengu.knowledge;
