package com.wengu.knowledge.controller;

import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import com.wengu.knowledge.dto.UpdateKnowledgePointRequest;
import com.wengu.knowledge.service.KnowledgePointService;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库中对知识点的操作（#25，决策 029）。都不返回数据：详情的格式由 #50 定，前端操作完重新取列表或详情。
 */
@RestController
@RequestMapping("/api/knowledge-points")
public class KnowledgePointController {

    private final KnowledgePointService knowledgePointService;
    private final CurrentUser currentUser;

    public KnowledgePointController(KnowledgePointService knowledgePointService, CurrentUser currentUser) {
        this.knowledgePointService = knowledgePointService;
        this.currentUser = currentUser;
    }

    /** 编辑标题、类型、要点，只改传了的字段；有改动时留一条修改前的快照。 */
    @PatchMapping("/{id}")
    public Result<Void> update(@PathVariable long id, @RequestBody UpdateKnowledgePointRequest request) {
        knowledgePointService.update(currentUser.id(), id, request);
        return Result.ok();
    }
}
