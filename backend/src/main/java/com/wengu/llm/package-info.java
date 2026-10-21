/**
 * 大模型接入：管理模型配置（model_config），封装对 OpenAI 兼容接口和 Ollama 的调用，以及失败时的降级链。
 * <p>
 * 底层基础模块。依赖：common。不依赖任何业务模块。API Key 不得出现在日志中。
 */
package com.wengu.llm;
