package com.wengu.review;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * review 不依赖其他业务模块，只能依赖 common（决策 002）。尤其不能依赖 knowledge：review 只通过 kp_id 工作。
 * <p>
 * 扫描源码中引用的 com.wengu.* 包，不为此引入 ArchUnit。
 */
class ReviewDependencyTest {

    private static final Path REVIEW_SOURCES = Path.of("src", "main", "java", "com", "wengu", "review");

    /** 匹配 import 和代码中写全的类名，如 com.wengu.knowledge.dto.X。 */
    private static final Pattern WENGU_PACKAGE = Pattern.compile("com\\.wengu\\.(\\w+)");

    @Test
    void review只依赖自己和common() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REVIEW_SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = WENGU_PACKAGE.matcher(Files.readString(file));
                while (m.find()) {
                    String module = m.group(1);
                    if (!module.equals("review") && !module.equals("common")) {
                        violations.add(REVIEW_SOURCES.relativize(file) + " 引用了 com.wengu." + module);
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
