package com.wengu.knowledge.controller;

import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import com.wengu.knowledge.dto.ConfirmDraftRequest;
import com.wengu.knowledge.dto.CreateDraftRequest;
import com.wengu.knowledge.dto.DraftSummary;
import com.wengu.knowledge.dto.DraftView;
import com.wengu.knowledge.service.ExtractionDraftService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    private final ExtractionDraftService draftService;
    private final CurrentUser currentUser;

    public DraftController(ExtractionDraftService draftService, CurrentUser currentUser) {
        this.draftService = draftService;
        this.currentUser = currentUser;
    }

    /** 对导入的材料抽取知识点。在后台执行，立即返回 status 为 RUNNING 的草稿，前端轮询 GET 查看进度。 */
    @PostMapping
    public Result<DraftView> create(@RequestBody CreateDraftRequest request) {
        return Result.ok(draftService.create(currentUser.id(), request));
    }

    /** 草稿列表，新的在前：未确认的全部返回，已确认的只返回最近 10 份。 */
    @GetMapping
    public Result<List<DraftSummary>> list() {
        return Result.ok(draftService.list(currentUser.id()));
    }

    @GetMapping("/{id}")
    public Result<DraftView> get(@PathVariable long id) {
        return Result.ok(draftService.get(currentUser.id(), id));
    }

    /** 重新抽取失败的批次，成功的批次不动。 */
    @PostMapping("/{id}/retry")
    public Result<DraftView> retry(@PathVariable long id) {
        return Result.ok(draftService.retry(currentUser.id(), id));
    }

    /** 确认草稿：勾选的条目生成知识点。不传 rejectedItemIds 时全部接受。 */
    @PostMapping("/{id}/confirm")
    public Result<DraftView> confirm(@PathVariable long id, @RequestBody(required = false) ConfirmDraftRequest request) {
        return Result.ok(draftService.confirm(currentUser.id(), id, request));
    }
}
