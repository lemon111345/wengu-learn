package com.wengu.chat.controller;

import com.wengu.chat.dto.FollowUpSummary;
import com.wengu.chat.service.FollowUpHistoryService;
import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 问答记录（决策 043）。 */
@RestController
@RequestMapping("/api/follow-ups")
public class FollowUpController {

    private final FollowUpHistoryService historyService;
    private final CurrentUser currentUser;

    public FollowUpController(FollowUpHistoryService historyService, CurrentUser currentUser) {
        this.historyService = historyService;
        this.currentUser = currentUser;
    }

    /** 有提问的接着问，最近活动的在前；keyword 匹配知识点标题、问题和回答。 */
    @GetMapping
    public Result<List<FollowUpSummary>> list(@RequestParam(required = false) String keyword) {
        return Result.ok(historyService.list(currentUser.id(), keyword));
    }
}
