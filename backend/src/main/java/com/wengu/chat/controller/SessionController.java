package com.wengu.chat.controller;

import com.wengu.chat.dto.SessionView;
import com.wengu.chat.dto.StartSessionRequest;
import com.wengu.chat.service.StudySessionService;
import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 学习会话（决策 025）。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final StudySessionService sessionService;
    private final CurrentUser currentUser;

    public SessionController(StudySessionService sessionService, CurrentUser currentUser) {
        this.sessionService = sessionService;
        this.currentUser = currentUser;
    }

    /** 开始学习一个知识点；有没结束的会话时返回那个会话。不调用模型，老师的第一句由 reply 生成。 */
    @PostMapping
    public Result<SessionView> start(@RequestBody StartSessionRequest request) {
        return Result.ok(sessionService.start(currentUser.id(), request));
    }

    /** 会话和全部消息，刷新后恢复用。 */
    @GetMapping("/{id}")
    public Result<SessionView> get(@PathVariable long id) {
        return Result.ok(sessionService.get(currentUser.id(), id));
    }
}
