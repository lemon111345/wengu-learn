package com.wengu.source.experiment;

import com.wengu.source.experiment.CodeExtractionResult.Evidence;
import com.wengu.source.experiment.CodeExtractionResult.KnowledgePoint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeExtractionSupportTest {

    private static final String FILE = "chat/service/ChatService.java";
    private static final Map<String, String> FILES = Map.of(FILE, "class ChatService { void send() { } }\n");

    @Test
    void 去掉行注释和块注释() {
        String source = """
                /** 类说明 */
                class A {
                    // 行注释
                    int x = 1; // 行尾注释
                    /* 块注释
                       第二行 */
                    int y = 2;
                }
                """;

        assertThat(CodeExtractionSupport.stripComments(source)).isEqualTo("""
                class A {

                    int x = 1;

                    int y = 2;
                }
                """);
    }

    @Test
    void 字符串里的注释符号不受影响() {
        String source = "class A { String url = \"https://x.com/*y*/\"; char c = '\"'; } // 去掉\n";

        assertThat(CodeExtractionSupport.stripComments(source))
                .isEqualTo("class A { String url = \"https://x.com/*y*/\"; char c = '\"'; }\n");
    }

    @Test
    void 文本块里的注释符号不受影响() {
        String source = "String s = \"\"\"\n    // 不是注释\n    \"\"\"; // 是注释\n";

        assertThat(CodeExtractionSupport.stripComments(source))
                .isEqualTo("String s = \"\"\"\n    // 不是注释\n    \"\"\";\n");
    }

    @Test
    void 兼容Windows换行() {
        assertThat(CodeExtractionSupport.stripComments("int x; // 注释\r\nint y;\r\n")).isEqualTo("int x;\nint y;\n");
    }

    @Test
    void 输入里每个文件前面带路径() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("a/A.java", "class A {}\n");
        files.put("b/B.java", "class B {}");

        assertThat(CodeExtractionSupport.renderInput(files)).isEqualTo("""
                以下是学生项目中的 2 个文件。

                ===== 文件：a/A.java =====
                class A {}

                ===== 文件：b/B.java =====
                class B {}
                """);
    }

    @Test
    void 真实存在的依据没有问题() {
        assertThat(CodeExtractionSupport.checkEvidence(FILES, kp(new Evidence(FILE, "ChatService#send")))).isEmpty();
        assertThat(CodeExtractionSupport.checkEvidence(FILES, kp(new Evidence(FILE, "ChatService")))).isEmpty();
        assertThat(CodeExtractionSupport.checkEvidence(FILES, kp(new Evidence(FILE, "ChatService.send()")))).isEmpty();
    }

    @Test
    void 不存在的文件和方法会被指出() {
        assertThat(CodeExtractionSupport.checkEvidence(FILES, kp(new Evidence("x/Missing.java", "Missing"))))
                .containsExactly("第 1 个知识点：文件不在输入中：x/Missing.java");
        assertThat(CodeExtractionSupport.checkEvidence(FILES, kp(new Evidence(FILE, "ChatService#stop"))))
                .containsExactly("第 1 个知识点：" + FILE + " 中没有 stop");
    }

    @Test
    void 方法名只匹配完整的标识符() {
        Map<String, String> files = Map.of(FILE, "class ChatService { void resend() { } }\n");

        assertThat(CodeExtractionSupport.checkEvidence(files, kp(new Evidence(FILE, "ChatService#send"))))
                .containsExactly("第 1 个知识点：" + FILE + " 中没有 send");
    }

    @Test
    void 没有依据的知识点会被指出() {
        KnowledgePoint noEvidence = new KnowledgePoint("CHAIN", List.of(), 1, null, "要点", null, "标题？", List.of());

        assertThat(CodeExtractionSupport.checkEvidence(FILES, noEvidence)).containsExactly("第 1 个知识点：没有代码依据");
    }

    private static KnowledgePoint kp(Evidence evidence) {
        return new KnowledgePoint("CHAIN", List.of(evidence), 1, null, "要点", null, "标题？", List.of());
    }
}
