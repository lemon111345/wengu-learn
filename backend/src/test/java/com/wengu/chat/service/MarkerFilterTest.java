package com.wengu.chat.service;

import com.wengu.chat.entity.Marker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 流式输出中的标记识别（决策 025）。纯计算，不需要 Spring。 */
class MarkerFilterTest {

    /** 依次推入各段，返回推给前端的全部文字。 */
    private static String run(MarkerFilter filter, List<String> chunks) {
        StringBuilder out = new StringBuilder();
        chunks.forEach(c -> out.append(filter.push(c)));
        out.append(filter.finish());
        return out.toString();
    }

    @Test
    void 没有标记时原样放出() {
        MarkerFilter filter = new MarkerFilter();

        assertThat(run(filter, List.of("HashMap 的", "扩容是", "怎样的？"))).isEqualTo("HashMap 的扩容是怎样的？");
        assertThat(filter.marker()).isNull();
        assertThat(filter.content()).isEqualTo("HashMap 的扩容是怎样的？");
    }

    @Test
    void 标记被拆在几段里也能识别且不推给前端() {
        MarkerFilter filter = new MarkerFilter();
        StringBuilder out = new StringBuilder();

        out.append(filter.push("参考答法……\n【学"));
        // 压住的“【学”还不能放出
        assertThat(out.toString()).isEqualTo("参考答法……\n");
        out.append(filter.push("习结"));
        out.append(filter.push("束】"));
        out.append(filter.finish());

        assertThat(out.toString()).isEqualTo("参考答法……\n");
        assertThat(filter.marker()).isEqualTo(Marker.END);
        // 存库的内容去掉标记留下的空行
        assertThat(filter.content()).isEqualTo("参考答法……");
    }

    @Test
    void 像标记开头但不是标记的字照常放出() {
        MarkerFilter filter = new MarkerFilter();

        assertThat(run(filter, List.of("注意【学", "生】的回答"))).isEqualTo("注意【学生】的回答");
        assertThat(filter.marker()).isNull();
        // 结尾压住的字在结束时放出
        MarkerFilter tail = new MarkerFilter();
        assertThat(run(tail, List.of("最后是一个【"))).isEqualTo("最后是一个【");
    }

    @Test
    void 完整作答的标记和同一段中的其他文字() {
        MarkerFilter filter = new MarkerFilter();

        assertThat(run(filter, List.of("请完整回答这个问题。\n【完整作答】"))).isEqualTo("请完整回答这个问题。\n");
        assertThat(filter.marker()).isEqualTo(Marker.RESTATE);
    }

    @Test
    void 两个标记都出现时学习结束优先() {
        MarkerFilter filter = new MarkerFilter();

        run(filter, List.of("【完整作答】……【学习结束】"));

        assertThat(filter.marker()).isEqualTo(Marker.END);
        assertThat(filter.content()).isEqualTo("……");
    }
}
