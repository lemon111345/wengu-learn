package com.wengu.knowledge.controller;

import com.wengu.common.result.Result;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.service.ExtractionDraftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private final ExtractionDraftService draftService;

    public DraftController(ExtractionDraftService draftService) {
        this.draftService = draftService;
    }

    /** 对导入的材料抽取知识点。同步调用模型，耗时 1～3 分钟；失败时也返回草稿，status 为 FAILED。 */
    @PostMapping
    public Result<DraftView> create(@RequestBody CreateDraftRequest request) {
        return Result.ok(draftService.create(request));
    }

    @GetMapping("/{id}")
    public Result<DraftView> get(@PathVariable long id) {
        return Result.ok(draftService.get(id));
    }
}
