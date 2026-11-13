package com.wengu.chat.controller;

import com.wengu.chat.dto.AnswerSummary;
import com.wengu.chat.service.AnswerService;
import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 答法速查（#112）。 */
@RestController
@RequestMapping("/api/answers")
public class AnswerController {

    private final AnswerService answerService;
    private final CurrentUser currentUser;

    public AnswerController(AnswerService answerService, CurrentUser currentUser) {
        this.answerService = answerService;
        this.currentUser = currentUser;
    }

    /** 学过的知识点各一条，带最近一次学习的参考答法，最近学的在前。 */
    @GetMapping
    public Result<List<AnswerSummary>> list() {
        return Result.ok(answerService.list(currentUser.id()));
    }
}
