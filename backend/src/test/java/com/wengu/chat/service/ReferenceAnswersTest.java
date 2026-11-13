package com.wengu.chat.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceAnswersTest {

    /** 开发库里实际出现过的几种写法 */
    @ParameterizedTest
    @ValueSource(strings = {
            "点评。\n\n**参考答法**：答法正文",
            "点评。\n\n**面试参考答法**：答法正文",
            "点评。\n\n参考答法：答法正文",
            "点评。\n\n面试参考答法：答法正文",
            "点评。\n\n【参考答法】答法正文",
            "点评。\n\n这道题的参考答法：答法正文",
            "点评。\n\n参考答法: 答法正文",
            "点评。\n\n参考答法如下：\n答法正文",
    })
    void 几种写法都能切出答法正文(String closing) {
        assertThat(ReferenceAnswers.extract(closing)).isEqualTo("答法正文");
    }

    @Test
    void 点评里也提到参考答法时取最后一次() {
        String closing = "你的作答和参考答法比漏了一点。\n\n参考答法：先校验，再写库。";

        assertThat(ReferenceAnswers.extract(closing)).isEqualTo("先校验，再写库。");
    }

    @Test
    void 答法有多段和代码时原样保留() {
        String closing = "点评。\n\n**参考答法**：`doCreateBean` 里分三步。\n\n第二段。";

        assertThat(ReferenceAnswers.extract(closing)).isEqualTo("`doCreateBean` 里分三步。\n\n第二段。");
    }

    @Test
    void 没有冒号时不去掉答法开头的加粗() {
        assertThat(ReferenceAnswers.extract("点评。\n\n参考答法\n\n**单例**放进缓存。")).isEqualTo("**单例**放进缓存。");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"讲得不错，下次复习时再看。", "参考答法：", "**参考答法**：  \n"})
    void 没有参考答法或者是空的时为null(String closing) {
        assertThat(ReferenceAnswers.extract(closing)).isNull();
    }
}
