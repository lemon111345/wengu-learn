package com.wengu.source.controller;

import com.wengu.common.result.Result;
import com.wengu.source.dto.ImportQuestionsRequest;
import com.wengu.source.dto.ImportedSource;
import com.wengu.source.service.SourceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    /** 导入面试题，返回切分出的题目供用户预览。会调用模型，耗时几秒到十几秒。 */
    @PostMapping
    public Result<ImportedSource> importQuestions(@RequestBody ImportQuestionsRequest request) {
        return Result.ok(sourceService.importQuestions(request));
    }
}
