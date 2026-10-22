package com.wengu.review.fsrs;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * FSRS-6 调度器：输入卡片当前状态、评分和复习时间，输出新的卡片状态。
 * <p>
 * 移植自 py-fsrs v6.3.2（https://github.com/open-spaced-repetition/py-fsrs）的 fsrs/scheduler.py，
 * MIT License，Copyright (c) 2022 Open Spaced Repetition，许可全文见仓库根目录 THIRD-PARTY-NOTICES.md。
 * <p>
 * 与 Python 版对齐时的几处细节（见对应方法的注释）：
 * <ol>
 *   <li>取整：Python 的 round() 是“四舍六入五成双”，必须用 {@link Math#rint}。用 Math.round 时，
 *       原始间隔恰好为 x.5 天的卡片会多排一天（用例 half_intervals 专门覆盖）</li>
 *   <li>天数：Python 的 timedelta.days 向下取整，这里用 {@link Math#floorDiv} 保持一致。目前天数只用于
 *       “&lt; 1”和“max(0, …)”判断，换成向零截断的 Duration.toDays() 结果相同，保持一致是为了以后不踩坑</li>
 *   <li>原代码写的是 math.e ** x，这里照写 Math.pow(Math.E, x)。实测换成 Math.exp(x) 在 1e-9 误差内无差别</li>
 * </ol>
 * 本类线程安全：所有字段在构造后不再改变。
 */
public final class Scheduler {

    /** FSRS-6 默认参数（21 个），与 py-fsrs 的 DEFAULT_PARAMETERS 相同。 */
    private static final double[] DEFAULT_PARAMETERS = {
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    };

    private static final double STABILITY_MIN = 0.001;
    private static final double INITIAL_STABILITY_MAX = 100.0;
    private static final double[] LOWER_BOUNDS = {
            STABILITY_MIN, STABILITY_MIN, STABILITY_MIN, STABILITY_MIN, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001,
            0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
    };
    private static final double[] UPPER_BOUNDS = {
            INITIAL_STABILITY_MAX, INITIAL_STABILITY_MAX, INITIAL_STABILITY_MAX, INITIAL_STABILITY_MAX, 10.0, 4.0, 4.0,
            0.75, 4.5, 0.8, 3.5, 5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
    };

    private static final double MIN_DIFFICULTY = 1.0;
    private static final double MAX_DIFFICULTY = 10.0;
    private static final long SECONDS_PER_DAY = 86_400;

    /** fuzz 范围：间隔落在 [start, end) 天的部分，每天贡献 factor 天的扰动幅度。与 py-fsrs 的 FUZZ_RANGES 相同。 */
    private record FuzzRange(double start, double end, double factor) {
    }

    private static final List<FuzzRange> FUZZ_RANGES = List.of(
            new FuzzRange(2.5, 7.0, 0.15),
            new FuzzRange(7.0, 20.0, 0.1),
            new FuzzRange(20.0, Double.POSITIVE_INFINITY, 0.05));

    private final double[] w;
    private final double desiredRetention;
    private final List<Duration> learningSteps;
    private final List<Duration> relearningSteps;
    private final int maximumInterval;
    private final boolean enableFuzzing;
    private final DoubleSupplier randomSource;
    private final double decay;
    private final double factor;

    /**
     * @param parameters       21 个模型参数，必须在合法范围内
     * @param desiredRetention 目标保持率，如 0.9
     * @param learningSteps    学习步，可以为空：为空时新卡复习一次就进入 REVIEW
     * @param relearningSteps  重学步，可以为空：为空时忘了也留在 REVIEW，只重新计算间隔
     * @param maximumInterval  最大间隔（天）
     * @param enableFuzzing    是否给复习间隔加随机扰动，避免同一天学的卡片总在同一天到期
     * @param randomSource     fuzz 用的随机数来源，返回 [0, 1) 的数；enableFuzzing 为 false 时可以为 null。
     *                         多线程共享本实例时，它本身必须线程安全
     */
    public Scheduler(double[] parameters, double desiredRetention, List<Duration> learningSteps,
                     List<Duration> relearningSteps, int maximumInterval,
                     boolean enableFuzzing, DoubleSupplier randomSource) {
        validateParameters(parameters);
        if (enableFuzzing) {
            Objects.requireNonNull(randomSource, "开启 fuzz 时必须提供 randomSource");
        }
        this.w = parameters.clone();
        this.desiredRetention = desiredRetention;
        this.learningSteps = List.copyOf(learningSteps);
        this.relearningSteps = List.copyOf(relearningSteps);
        this.maximumInterval = maximumInterval;
        this.enableFuzzing = enableFuzzing;
        this.randomSource = randomSource;
        this.decay = -w[20];
        this.factor = Math.pow(0.9, 1 / decay) - 1;
    }

    /**
     * 本项目的默认配置：默认参数、目标保持率 0.9、最大间隔 36500 天，学习步和重学步都为空，开启 fuzz。
     * 每个知识点是一段几分钟的 AI 对话，同一天重复出现体验差且浪费模型调用，见决策记录 003。
     * 随机数用 ThreadLocalRandom：每个线程各用各的，实例可以安全地在多个请求间共享。
     */
    public static Scheduler projectDefault() {
        return new Scheduler(DEFAULT_PARAMETERS, 0.9, List.of(), List.of(), 36500,
                true, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** 返回默认参数的副本。 */
    public static double[] defaultParameters() {
        return DEFAULT_PARAMETERS.clone();
    }

    /** 卡片在给定时间的保持率（回忆起来的概率），新卡返回 0。 */
    public double retrievability(Card card, Instant now) {
        if (card.lastReview() == null || card.stability() == null) {
            return 0;
        }
        long elapsedDays = Math.max(0, daysBetween(card.lastReview(), now));
        return Math.pow(1 + factor * elapsedDays / card.stability(), decay);
    }

    /** 复习一次：返回复习后的新卡片，原卡片不变。 */
    public Card review(Card card, Rating rating, Instant reviewTime) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(reviewTime, "reviewTime");

        Long daysSinceLastReview = card.lastReview() == null ? null : daysBetween(card.lastReview(), reviewTime);

        // 第一步：更新记忆参数（稳定性、难度）
        double stability;
        double difficulty;
        if (card.stability() == null || card.difficulty() == null) {
            if (card.state() != State.LEARNING) {
                throw new IllegalArgumentException("只有 LEARNING 状态的卡片可以没有稳定性和难度: " + card);
            }
            stability = initialStability(rating);
            difficulty = initialDifficulty(rating, true);
        } else if (daysSinceLastReview != null && daysSinceLastReview < 1) {
            // 同一天内再次复习：用短期稳定性公式
            stability = shortTermStability(card.stability(), rating);
            difficulty = nextDifficulty(card.difficulty(), rating);
        } else {
            stability = nextStability(card.difficulty(), card.stability(), retrievability(card, reviewTime), rating);
            difficulty = nextDifficulty(card.difficulty(), rating);
        }

        // 第二步：决定新状态和下次间隔
        Transition next = switch (card.state()) {
            case LEARNING -> throughSteps(State.LEARNING, learningSteps, requireStep(card), rating, stability);
            case RELEARNING -> throughSteps(State.RELEARNING, relearningSteps, requireStep(card), rating, stability);
            case REVIEW -> fromReview(rating, stability);
        };

        // 第三步：只对进入 REVIEW 的卡片做 fuzz，学习步、重学步的分钟级间隔保持不变
        Duration interval = enableFuzzing && next.state() == State.REVIEW
                ? fuzz(next.interval())
                : next.interval();

        return new Card(next.state(), next.step(), stability, difficulty, reviewTime.plus(interval), reviewTime);
    }

    /** 复习后的状态、步数和下次间隔。 */
    private record Transition(State state, Integer step, Duration interval) {
    }

    /**
     * 学习中和重学中的卡片按步长推进，两者规则相同，只是步长列表不同。
     * （Python 版把这段逻辑分别写在 Learning 和 Relearning 两个分支里，内容一致。）
     */
    private Transition throughSteps(State state, List<Duration> steps, int step, Rating rating, double stability) {
        if (steps.isEmpty() || (step >= steps.size() && rating != Rating.AGAIN)) {
            return toReview(stability);
        }
        return switch (rating) {
            case AGAIN -> new Transition(state, 0, steps.get(0));
            case HARD -> {
                Duration interval;
                if (step == 0 && steps.size() == 1) {
                    interval = scale(steps.get(0), 1.5);
                } else if (step == 0) {
                    interval = scale(steps.get(0).plus(steps.get(1)), 0.5);
                } else {
                    interval = steps.get(step);
                }
                yield new Transition(state, step, interval);
            }
            case GOOD -> step + 1 == steps.size()
                    ? toReview(stability)
                    : new Transition(state, step + 1, steps.get(step + 1));
            case EASY -> toReview(stability);
        };
    }

    private Transition fromReview(Rating rating, double stability) {
        if (rating == Rating.AGAIN && !relearningSteps.isEmpty()) {
            return new Transition(State.RELEARNING, 0, relearningSteps.get(0));
        }
        return toReview(stability);
    }

    private Transition toReview(double stability) {
        return new Transition(State.REVIEW, null, Duration.ofDays(nextInterval(stability)));
    }

    // ===== FSRS-6 公式，与 py-fsrs 的同名私有方法一一对应 =====

    private double initialStability(Rating rating) {
        return clampStability(w[rating.value() - 1]);
    }

    private double initialDifficulty(Rating rating, boolean clamp) {
        double difficulty = w[4] - Math.pow(Math.E, w[5] * (rating.value() - 1)) + 1;
        return clamp ? clampDifficulty(difficulty) : difficulty;
    }

    /** 下次间隔（整天）。Python 的 round() 是四舍六入五成双，对应 Math.rint。 */
    private long nextInterval(double stability) {
        double interval = (stability / factor) * (Math.pow(desiredRetention, 1 / decay) - 1);
        long days = (long) Math.rint(interval);
        return Math.min(Math.max(days, 1), maximumInterval);
    }

    private double shortTermStability(double stability, Rating rating) {
        double increase = Math.pow(Math.E, w[17] * (rating.value() - 3 + w[18])) * Math.pow(stability, -w[19]);
        if (rating != Rating.AGAIN) {
            increase = Math.max(increase, 1.0);
        }
        return clampStability(stability * increase);
    }

    private double nextDifficulty(double difficulty, Rating rating) {
        double target = initialDifficulty(Rating.EASY, false);
        double delta = -(w[6] * (rating.value() - 3));
        // 线性阻尼：难度越接近 10，变化越小
        double damped = difficulty + (10.0 - difficulty) * delta / 9.0;
        // 均值回归：向“简单”的初始难度靠拢一点
        double next = w[7] * target + (1 - w[7]) * damped;
        return clampDifficulty(next);
    }

    private double nextStability(double difficulty, double stability, double retrievability, Rating rating) {
        double next = rating == Rating.AGAIN
                ? nextForgetStability(difficulty, stability, retrievability)
                : nextRecallStability(difficulty, stability, retrievability, rating);
        return clampStability(next);
    }

    private double nextForgetStability(double difficulty, double stability, double retrievability) {
        double longTerm = w[11]
                * Math.pow(difficulty, -w[12])
                * (Math.pow(stability + 1, w[13]) - 1)
                * Math.pow(Math.E, (1 - retrievability) * w[14]);
        double shortTerm = stability / Math.pow(Math.E, w[17] * w[18]);
        return Math.min(longTerm, shortTerm);
    }

    private double nextRecallStability(double difficulty, double stability, double retrievability, Rating rating) {
        double hardPenalty = rating == Rating.HARD ? w[15] : 1;
        double easyBonus = rating == Rating.EASY ? w[16] : 1;
        return stability * (1
                + Math.pow(Math.E, w[8])
                * (11 - difficulty)
                * Math.pow(stability, -w[9])
                * (Math.pow(Math.E, (1 - retrievability) * w[10]) - 1)
                * hardPenalty
                * easyBonus);
    }

    /**
     * 在原间隔附近随机取一个整天数，对应 py-fsrs 的 _get_fuzzed_interval。
     * 间隔越长，扰动幅度越大；不足 2.5 天的间隔不扰动，也不消耗随机数。
     */
    private Duration fuzz(Duration interval) {
        long days = interval.toDays();
        if (days < 2.5) {
            return interval;
        }
        double delta = 1.0;
        for (FuzzRange range : FUZZ_RANGES) {
            delta += range.factor() * Math.max(Math.min((double) days, range.end()) - range.start(), 0.0);
        }
        long minDays = (long) Math.rint(days - delta);
        long maxDays = (long) Math.rint(days + delta);
        minDays = Math.max(2, minDays);
        maxDays = Math.min(maxDays, maximumInterval);
        minDays = Math.min(minDays, maxDays);

        // 在 [minDays, maxDays] 中取值：random * (区间长度 + 1) + minDays，再取整
        double fuzzed = randomSource.getAsDouble() * (maxDays - minDays + 1) + minDays;
        return Duration.ofDays(Math.min((long) Math.rint(fuzzed), maximumInterval));
    }

    private static double clampStability(double stability) {
        return Math.max(stability, STABILITY_MIN);
    }

    private static double clampDifficulty(double difficulty) {
        return Math.min(Math.max(difficulty, MIN_DIFFICULTY), MAX_DIFFICULTY);
    }

    // ===== 时间计算，对齐 Python datetime 的行为 =====

    /** 对应 Python 的 (to - from).days：向下取整。Duration.getSeconds() 本身也是向下取整。 */
    private static long daysBetween(Instant from, Instant to) {
        return Math.floorDiv(Duration.between(from, to).getSeconds(), SECONDS_PER_DAY);
    }

    /** 对应 Python 的 timedelta * float：结果按微秒四舍六入五成双。 */
    private static Duration scale(Duration duration, double multiplier) {
        long micros = Math.floorDiv(duration.toNanos(), 1000);
        return Duration.ofNanos((long) Math.rint(micros * multiplier) * 1000);
    }

    private static int requireStep(Card card) {
        if (card.step() == null) {
            throw new IllegalArgumentException(card.state() + " 状态的卡片必须有 step: " + card);
        }
        return card.step();
    }

    private static void validateParameters(double[] parameters) {
        if (parameters.length != LOWER_BOUNDS.length) {
            throw new IllegalArgumentException(
                    "需要 " + LOWER_BOUNDS.length + " 个参数，实际为 " + parameters.length);
        }
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            if (!(LOWER_BOUNDS[i] <= parameters[i] && parameters[i] <= UPPER_BOUNDS[i])) {
                errors.add("parameters[" + i + "] = " + parameters[i]
                        + " 超出范围 [" + LOWER_BOUNDS[i] + ", " + UPPER_BOUNDS[i] + "]");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("参数超出范围:\n" + String.join("\n", errors));
        }
    }
}
