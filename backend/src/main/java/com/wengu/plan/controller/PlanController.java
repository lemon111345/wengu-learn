package com.wengu.plan.controller;

import com.wengu.common.context.CurrentUser;
import com.wengu.common.result.Result;
import com.wengu.plan.dto.TodayPlan;
import com.wengu.plan.service.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService planService;
    private final CurrentUser currentUser;

    public PlanController(PlanService planService, CurrentUser currentUser) {
        this.planService = planService;
        this.currentUser = currentUser;
    }

    /** 今天的计划：到期复习 + 新知识。每次实时计算，修改设置后立即生效。 */
    @GetMapping("/today")
    public Result<TodayPlan> today() {
        return Result.ok(planService.today(currentUser.id()));
    }
}
