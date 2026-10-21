/**
 * 大模型接入：封装对 OpenAI 兼容接口和 Ollama 的调用，以及失败时的降级链。
 * <p>
 * 依赖：user（读取模型配置）、common。API Key 不得出现在日志中。
 */
package com.wengu.llm;
