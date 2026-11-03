package com.wengu.source.controller;

import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import com.wengu.source.dto.ImportQuestionsRequest;
import com.wengu.source.dto.ImportedSource;
import com.wengu.source.entity.SourceType;
import com.wengu.source.service.SourceService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;
    private final CurrentUser currentUser;

    public SourceController(SourceService sourceService, CurrentUser currentUser) {
        this.sourceService = sourceService;
        this.currentUser = currentUser;
    }

    /** 导入粘贴的资料，返回识别或整理出的题目供用户预览。会调用模型，耗时几秒到几十秒。 */
    @PostMapping
    public Result<ImportedSource> importQuestions(@RequestBody ImportQuestionsRequest request) {
        return Result.ok(sourceService.importQuestions(currentUser.id(), request));
    }

    /**
     * 上传文件导入（#26）：PDF、DOCX、Markdown、txt，最大 10 MB。提取文字后和粘贴的资料一样处理，返回值相同。
     *
     * @param name 材料名称，不填时用文件名
     * @param type 资料类型，不填时为 QUESTION（题目清单）
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ImportedSource> upload(@RequestPart("file") MultipartFile file,
                                         @RequestParam(required = false) String name,
                                         @RequestParam(required = false) SourceType type) throws IOException {
        return Result.ok(sourceService.importFile(currentUser.id(), file.getOriginalFilename(), file.getBytes(),
                name, type));
    }
}
