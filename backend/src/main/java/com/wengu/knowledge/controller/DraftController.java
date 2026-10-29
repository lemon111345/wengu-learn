package com.wengu.knowledge.controller;

import com.wengu.common.result.Result;
import com.wengu.knowledge.dto.ConfirmDraftRequest;
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

    /** 对导入的材料抽取知识点。在后台执行，立即返回 status 为 RUNNING 的草稿，前端轮询 GET 查看进度。 */
    @PostMapping
    public Result<DraftView> create(@RequestBody CreateDraftRequest request) {
        return Result.ok(draftService.create(request));
    }

    @GetMapping("/{id}")
    public Result<DraftView> get(@PathVariable long id) {
        return Result.ok(draftService.get(id));
    }

    /** 重新抽取失败的批次，成功的批次不动。 */
    @PostMapping("/{id}/retry")
    public Result<DraftView> retry(@PathVariable long id) {
        return Result.ok(draftService.retry(id));
    }

    /** 确认草稿：勾选的条目生成知识点。不传 rejectedItemIds 时全部接受。 */
    @PostMapping("/{id}/confirm")
    public Result<DraftView> confirm(@PathVariable long id, @RequestBody(required = false) ConfirmDraftRequest request) {
        return Result.ok(draftService.confirm(id, request));
    }
}
