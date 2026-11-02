package com.wengu.chat.controller;

import com.wengu.chat.dto.RateRequest;
import com.wengu.chat.dto.RatingOption;
import com.wengu.chat.dto.RatingSuggestionView;
import com.wengu.chat.dto.SendMessageRequest;
import com.wengu.chat.dto.SessionView;
import com.wengu.chat.dto.StartSessionRequest;
import com.wengu.chat.service.ChatService;
import com.wengu.chat.service.RatingService;
import com.wengu.chat.service.RatingSuggestionService;
import com.wengu.chat.service.StudySessionService;
import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/** 学习会话（决策 025）。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final StudySessionService sessionService;
    private final ChatService chatService;
    private final RatingSuggestionService ratingSuggestionService;
    private final RatingService ratingService;
    private final CurrentUser currentUser;

    public SessionController(StudySessionService sessionService, ChatService chatService,
                             RatingSuggestionService ratingSuggestionService, RatingService ratingService,
                             CurrentUser currentUser) {
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.ratingSuggestionService = ratingSuggestionService;
        this.ratingService = ratingService;
        this.currentUser = currentUser;
    }

    /** 开始学习一个知识点；有没结束的会话时返回那个会话。不调用模型，老师的第一句由 reply 生成。 */
    @PostMapping
    public Result<SessionView> start(@RequestBody StartSessionRequest request) {
        return Result.ok(sessionService.start(currentUser.id(), request));
    }

    /**
     * 进入接着问：id 是已经结束的学习会话，返回它的接着问会话，没有就新建。
     * 之后的提问、重试、读取都用会话的通用接口，学生先问，没有开场白。
     */
    @PostMapping("/{id}/follow-up")
    public Result<SessionView> followUp(@PathVariable long id) {
        return Result.ok(sessionService.startFollowUp(currentUser.id(), id));
    }

    /**
     * AI 评分建议：学完后前端调用，第一次会调用模型（十几到二十几秒），之后直接返回存好的结果。
     * 评分不依赖它，用户可以先评分。
     */
    @PostMapping("/{id}/rating-suggestion")
    public Result<RatingSuggestionView> ratingSuggestion(@PathVariable long id) {
        return Result.ok(ratingSuggestionService.suggest(currentUser.id(), id));
    }

    /** 评分预览：四种评分分别对应的下次复习日期（学习日和距今天几天），不保存。 */
    @GetMapping("/{id}/rating-preview")
    public Result<List<RatingOption>> ratingPreview(@PathVariable long id) {
        return Result.ok(ratingService.preview(currentUser.id(), id));
    }

    /** 确认评分：更新调度状态、写评分流水，返回下次复习日期。一次学习只能评一次。 */
    @PostMapping("/{id}/rating")
    public Result<RatingOption> rate(@PathVariable long id, @RequestBody RateRequest request) {
        return Result.ok(ratingService.rate(currentUser.id(), id, request));
    }

    /** 会话和全部消息，刷新后恢复用。 */
    @GetMapping("/{id}")
    public Result<SessionView> get(@PathVariable long id) {
        return Result.ok(sessionService.get(currentUser.id(), id));
    }

    // 下面三个接口的响应是 text/event-stream：delta（一段文字）、done（回复完成）、error（出错）。
    // 用 POST，前端用 fetch 读流：EventSource 只能发 GET，带不上回答内容和 CSRF 令牌（决策 025）。
    // 校验不通过时在推送之前就返回统一格式的 JSON 错误

    /** 生成老师的下一条回复：刚开始时的第一句，或者出错后重试。 */
    @PostMapping("/{id}/reply")
    public Flux<ServerSentEvent<Object>> reply(@PathVariable long id) {
        return chatService.reply(currentUser.id(), id);
    }

    /** 学生回答，老师流式回复。 */
    @PostMapping("/{id}/messages")
    public Flux<ServerSentEvent<Object>> send(@PathVariable long id, @RequestBody SendMessageRequest request) {
        return chatService.send(currentUser.id(), id, request);
    }

    /** “这个我会，直接完整作答”。 */
    @PostMapping("/{id}/skip-explanation")
    public Flux<ServerSentEvent<Object>> skipExplanation(@PathVariable long id) {
        return chatService.skipExplanation(currentUser.id(), id);
    }
}
