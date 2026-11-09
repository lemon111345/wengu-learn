package com.wengu.source.service;

import com.wengu.NoDatabase;
import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.llm.dto.LlmImage;
import com.wengu.llm.service.LlmException;
import com.wengu.llm.service.LlmService;
import com.wengu.source.dto.ImageRecognitionView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 截图识别（决策 044）。真实的 Redis，模型换成模拟对象，线程池换成同步执行：提交返回时任务已经做完。
 */
@SpringBootTest(properties = {NoDatabase.PROPERTY, NoDatabase.LAZY_PROPERTY, NoDatabase.NO_RECOVERY_PROPERTY})
class ImageRecognitionServiceTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER = 2L;
    private static final int MB = 1024 * 1024;

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @TestBean(name = "imageRecognitionExecutor")
    private TaskExecutor executor;

    static TaskExecutor executor() {
        return new SyncTaskExecutor();
    }

    @MockitoBean
    private LlmService llmService;

    @Autowired
    private ImageRecognitionService service;

    @Autowired
    private StringRedisTemplate redis;

    private static byte[] withHeader(int size, int... header) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < header.length; i++) {
            bytes[i] = (byte) header[i];
        }
        return bytes;
    }

    private static byte[] png(int size) {
        return withHeader(size, 0x89, 'P', 'N', 'G');
    }

    private static byte[] jpeg() {
        return withHeader(16, 0xFF, 0xD8, 0xFF);
    }

    private static byte[] webp() {
        return withHeader(16, 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
    }

    private void modelReplies(String text) {
        when(llmService.callWithImages(anyString(), anyString(), anyList(), any())).thenReturn(text);
    }

    private ImageRecognitionView recognize(byte[]... files) {
        return service.status(USER_ID, service.submit(USER_ID, List.of(files)).taskId());
    }

    private void assertRejected(String message, byte[]... files) {
        assertThatThrownBy(() -> service.submit(USER_ID, List.of(files)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    @Test
    void 识别成功时返回文字_去掉首尾空白() {
        modelReplies("  1. HashMap 的扩容机制是怎样的？\n");

        assertThat(recognize(png(16))).isEqualTo(ImageRecognitionView.done("1. HashMap 的扩容机制是怎样的？"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 图片按传入的顺序发送_类型按文件开头的字节判断() {
        modelReplies("文字");
        byte[] first = png(16);
        byte[] second = jpeg();
        byte[] third = webp();

        recognize(first, second, third);

        ArgumentCaptor<List<LlmImage>> images = ArgumentCaptor.forClass(List.class);
        verify(llmService).callWithImages(anyString(), anyString(), images.capture(), eq(0.0));
        assertThat(images.getValue()).extracting(LlmImage::mimeType)
                .containsExactly("image/png", "image/jpeg", "image/webp");
        assertThat(images.getValue()).extracting(LlmImage::data).containsExactly(first, second, third);
    }

    @Test
    void 模型拒绝图片时任务失败并给出提示() {
        when(llmService.callWithImages(anyString(), anyString(), anyList(), any()))
                .thenThrow(new LlmException(ErrorCode.LLM_IMAGE_UNSUPPORTED));

        assertThat(recognize(png(16))).isEqualTo(
                ImageRecognitionView.failed(ErrorCode.LLM_IMAGE_UNSUPPORTED.getMessage()));
    }

    @Test
    void 模型忽略图片回答标记时也提示不支持() {
        modelReplies(" NO_IMAGE\n");

        assertThat(recognize(png(16))).isEqualTo(
                ImageRecognitionView.failed(ErrorCode.LLM_IMAGE_UNSUPPORTED.getMessage()));
    }

    @Test
    void 图片里没有文字时提示() {
        modelReplies("NO_TEXT");

        assertThat(recognize(png(16))).isEqualTo(ImageRecognitionView.failed("图片里没有识别出文字"));
    }

    @Test
    void 意外错误时给通用提示_不暴露内部信息() {
        when(llmService.callWithImages(anyString(), anyString(), anyList(), any()))
                .thenThrow(new IllegalStateException("内部细节"));

        assertThat(recognize(png(16))).isEqualTo(ImageRecognitionView.failed("截图没有识别出来，可以重试"));
    }

    @Test
    void 张数大小格式不对时拒绝() {
        assertRejected("请选择要识别的图片");
        byte[] small = png(16);
        assertRejected("一次最多识别 5 张图片", small, small, small, small, small, small);
        assertRejected("每张图片不能超过 5 MB", png(5 * MB + 1));
        assertRejected("图片合计不能超过 10 MB", png(4 * MB), png(4 * MB), png(4 * MB));
        assertRejected("只支持 PNG、JPG、WebP 格式的图片", "这不是图片，只是文字".getBytes(StandardCharsets.UTF_8));
        assertRejected("只支持 PNG、JPG、WebP 格式的图片", new byte[]{0x47});
    }

    @Test
    void 边界值可以提交() {
        modelReplies("文字");

        assertThat(recognize(png(5 * MB), png(5 * MB)).status()).isEqualTo(ImageRecognitionView.Status.DONE);
    }

    @Test
    void 别人的任务和不存在的任务查不到() {
        modelReplies("文字");
        String taskId = service.submit(USER_ID, List.of(png(16))).taskId();

        for (Runnable query : List.<Runnable>of(() -> service.status(OTHER_USER, taskId),
                () -> service.status(USER_ID, "不存在"))) {
            assertThatThrownBy(query::run).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }
    }

    @Test
    void 任务30分钟后过期_不保存图片() {
        modelReplies("文字");
        byte[] image = png(64);
        Arrays.fill(image, 8, 64, (byte) 7);

        String taskId = service.submit(USER_ID, List.of(image)).taskId();

        String key = ImageRecognitionService.KEY_PREFIX + USER_ID + ":" + taskId;
        assertThat(redis.getExpire(key, TimeUnit.SECONDS))
                .isBetween(Duration.ofMinutes(29).toSeconds(), Duration.ofMinutes(30).toSeconds());
        assertThat(redis.opsForValue().get(key)).doesNotContain("image").hasSizeLessThan(64);
    }
}
