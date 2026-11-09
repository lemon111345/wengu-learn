package com.wengu.source.service;

import com.wengu.common.exception.BusinessException;
import com.wengu.common.result.ErrorCode;
import com.wengu.llm.dto.LlmImage;
import com.wengu.llm.service.LlmService;
import com.wengu.source.dto.ImageRecognitionTask;
import com.wengu.source.dto.ImageRecognitionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 识别截图中的文字（决策 044）。异步：提交后马上返回任务 id，由专用线程池调用模型，结果放 Redis，30 分钟后自动过期。
 * <p>
 * 这里只产出文字，不写 source 表：文字由用户核对后，和粘贴的原文一样导入。图片只在处理期间留在内存中。
 */
@Service
public class ImageRecognitionService {

    public static final int MAX_IMAGES = 5;
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    /** 和 spring.servlet.multipart、Nginx 的上传限制一致 */
    public static final long MAX_TOTAL_BYTES = 10L * 1024 * 1024;

    static final Duration TASK_TTL = Duration.ofMinutes(30);
    static final String KEY_PREFIX = "wengu:image-recognition:";

    /** 提示词约定的标记：服务商忽略了图片、照样回答时，靠它发现模型不支持图片 */
    static final String NO_IMAGE = "NO_IMAGE";
    static final String NO_TEXT = "NO_TEXT";

    private static final Logger log = LoggerFactory.getLogger(ImageRecognitionService.class);
    private static final String PROMPT_PATH = "prompts/image-text-v1.md";
    /** 转写要逐字，不需要变化 */
    private static final double TEMPERATURE = 0.0;

    private final LlmService llmService;
    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final TaskExecutor executor;
    private final String prompt;

    public ImageRecognitionService(LlmService llmService, StringRedisTemplate redis, JsonMapper jsonMapper,
                                   @Qualifier("imageRecognitionExecutor") TaskExecutor executor) {
        this.llmService = llmService;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.executor = executor;
        try {
            this.prompt = new ClassPathResource(PROMPT_PATH).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 prompt 失败：" + PROMPT_PATH, e);
        }
    }

    /**
     * 提交一批截图，按传入的顺序识别。
     *
     * @throws BusinessException 张数、大小、格式不对时为 BAD_REQUEST；排队的任务太多时为 CONFLICT
     */
    public ImageRecognitionTask submit(long userId, List<byte[]> files) {
        List<LlmImage> images = validated(files);
        String taskId = UUID.randomUUID().toString();
        save(userId, taskId, ImageRecognitionView.running());
        try {
            executor.execute(() -> run(userId, taskId, images));
        } catch (TaskRejectedException e) {
            redis.delete(key(userId, taskId));
            throw new BusinessException(ErrorCode.CONFLICT, "正在识别的截图太多，请稍后再试");
        }
        return new ImageRecognitionTask(taskId);
    }

    /** @throws BusinessException 任务已过期、不存在或不属于这个用户时为 NOT_FOUND */
    public ImageRecognitionView status(long userId, String taskId) {
        String json = redis.opsForValue().get(key(userId, taskId));
        if (json == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "识别任务不存在或已过期，请重新识别");
        }
        return jsonMapper.readValue(json, ImageRecognitionView.class);
    }

    private void run(long userId, String taskId, List<LlmImage> images) {
        ImageRecognitionView result;
        try {
            result = ImageRecognitionView.done(recognize(images));
        } catch (BusinessException e) {
            // 模型调用失败的提示已经写清楚了怎么办（决策 032）
            result = ImageRecognitionView.failed(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("识别截图失败: {}", e.toString());
            result = ImageRecognitionView.failed("截图没有识别出来，可以重试");
        }
        save(userId, taskId, result);
    }

    private String recognize(List<LlmImage> images) {
        String text = llmService.callWithImages(prompt, "请转写这 " + images.size() + " 张图片中的文字。", images, TEMPERATURE)
                .strip();
        if (text.equals(NO_IMAGE)) {
            throw new BusinessException(ErrorCode.LLM_IMAGE_UNSUPPORTED);
        }
        if (text.equals(NO_TEXT)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片里没有识别出文字");
        }
        return text;
    }

    private void save(long userId, String taskId, ImageRecognitionView view) {
        redis.opsForValue().set(key(userId, taskId), jsonMapper.writeValueAsString(view), TASK_TTL);
    }

    /** 键里带 user_id：别人的任务查不到 */
    private static String key(long userId, String taskId) {
        return KEY_PREFIX + userId + ":" + taskId;
    }

    private static List<LlmImage> validated(List<byte[]> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要识别的图片");
        }
        if (files.size() > MAX_IMAGES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "一次最多识别 " + MAX_IMAGES + " 张图片");
        }
        List<LlmImage> images = new ArrayList<>();
        long total = 0;
        for (byte[] file : files) {
            if (file.length > MAX_IMAGE_BYTES) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "每张图片不能超过 5 MB");
            }
            total += file.length;
            images.add(new LlmImage(file, mimeType(file)));
        }
        if (total > MAX_TOTAL_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片合计不能超过 10 MB");
        }
        return images;
    }

    /** 按文件开头的字节判断格式，不信任扩展名和客户端声明的类型 */
    private static String mimeType(byte[] file) {
        if (startsWith(file, 0, 0x89, 'P', 'N', 'G')) {
            return "image/png";
        }
        if (startsWith(file, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(file, 0, 'R', 'I', 'F', 'F') && startsWith(file, 8, 'W', 'E', 'B', 'P')) {
            return "image/webp";
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "只支持 PNG、JPG、WebP 格式的图片");
    }

    private static boolean startsWith(byte[] file, int offset, int... expected) {
        if (file.length < offset + expected.length) {
            return false;
        }
        byte[] actual = Arrays.copyOfRange(file, offset, offset + expected.length);
        for (int i = 0; i < expected.length; i++) {
            if ((actual[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
