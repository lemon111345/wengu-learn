package com.wengu.knowledge.controller;

import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import com.wengu.knowledge.dto.KnowledgePointDetail;
import com.wengu.knowledge.dto.KnowledgePointIdsRequest;
import com.wengu.knowledge.dto.KnowledgePointType;
import com.wengu.knowledge.dto.LibrarySort;
import com.wengu.knowledge.dto.LibraryView;
import com.wengu.knowledge.dto.Mastery;
import com.wengu.knowledge.dto.MergeKnowledgePointsRequest;
import com.wengu.knowledge.dto.SortDirection;
import com.wengu.knowledge.dto.UpdateKnowledgePointRequest;
import com.wengu.knowledge.service.KnowledgeLibraryService;
import com.wengu.knowledge.service.KnowledgePointService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库：列表和详情（#50，决策 030），以及对知识点的操作（#25，决策 029）。
 * 操作都不返回数据，前端操作完重新取列表或详情。
 */
@RestController
@RequestMapping("/api/knowledge-points")
public class KnowledgePointController {

    private final KnowledgePointService knowledgePointService;
    private final KnowledgeLibraryService libraryService;
    private final CurrentUser currentUser;

    public KnowledgePointController(KnowledgePointService knowledgePointService,
                                    KnowledgeLibraryService libraryService, CurrentUser currentUser) {
        this.knowledgePointService = knowledgePointService;
        this.libraryService = libraryService;
        this.currentUser = currentUser;
    }

    /**
     * 知识库列表：按掌握程度和类型筛选（都不传表示全部），按学习顺序、掌握程度或下次复习排序。
     * 参数的值不合法时 400。
     */
    @GetMapping
    public Result<LibraryView> list(@RequestParam(required = false) Mastery mastery,
                                    @RequestParam(required = false) KnowledgePointType type,
                                    @RequestParam(defaultValue = "ORDER") LibrarySort sort,
                                    @RequestParam(defaultValue = "ASC") SortDirection direction) {
        return Result.ok(libraryService.list(currentUser.id(), mastery, type, sort, direction));
    }

    /** 知识点详情：掌握程度、下次复习、要点和 AI 原文、学习记录、来源。不存在或已删除时 404。 */
    @GetMapping("/{id}")
    public Result<KnowledgePointDetail> detail(@PathVariable long id) {
        return Result.ok(libraryService.detail(currentUser.id(), id));
    }

    /** 编辑标题、类型、要点，只改传了的字段；有改动时留一条修改前的快照。 */
    @PatchMapping("/{id}")
    public Result<Void> update(@PathVariable long id, @RequestBody UpdateKnowledgePointRequest request) {
        knowledgePointService.update(currentUser.id(), id, request);
        return Result.ok();
    }

    /** 暂停复习（批量）：不再进每日计划，复习进度不动。 */
    @PostMapping("/suspend")
    public Result<Void> suspend(@RequestBody KnowledgePointIdsRequest request) {
        knowledgePointService.suspend(currentUser.id(), request.ids());
        return Result.ok();
    }

    /** 恢复复习（批量）。 */
    @PostMapping("/resume")
    public Result<Void> resume(@RequestBody KnowledgePointIdsRequest request) {
        knowledgePointService.resume(currentUser.id(), request.ids());
        return Result.ok();
    }

    /**
     * 删除（批量，软删除）。不用 DELETE 方法：要在请求体中传多个 id，DELETE 的请求体有的代理会丢掉。
     */
    @PostMapping("/delete")
    public Result<Void> delete(@RequestBody KnowledgePointIdsRequest request) {
        knowledgePointService.delete(currentUser.id(), request.ids());
        return Result.ok();
    }

    /** 撤销删除（批量）。 */
    @PostMapping("/restore")
    public Result<Void> restore(@RequestBody KnowledgePointIdsRequest request) {
        knowledgePointService.restore(currentUser.id(), request.ids());
        return Result.ok();
    }

    /** 合并：保留主知识点的标题、要点和复习进度，其他的来源并到主知识点上，然后删除（不能撤销）。 */
    @PostMapping("/merge")
    public Result<Void> merge(@RequestBody MergeKnowledgePointsRequest request) {
        knowledgePointService.merge(currentUser.id(), request.primaryId(), request.mergedIds());
        return Result.ok();
    }
}
