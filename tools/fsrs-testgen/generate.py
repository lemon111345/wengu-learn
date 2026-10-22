# /// script
# requires-python = ">=3.10"
# dependencies = ["fsrs==6.3.2"]
# ///
"""
用 py-fsrs 6.3.2 生成 Java 版 FSRS（com.wengu.review.fsrs）的比对用例。

运行方式（在仓库根目录）：
    uv run tools/fsrs-testgen/generate.py

输出：backend/src/test/resources/fsrs/py-fsrs-6.3.2-cases.json

用例结构：每个用例是“一种调度器配置 + 一张新卡 + 一串复习”，每次复习记录
评分、复习时间、复习前的保持率、fuzz 用到的随机数和复习后的卡片状态。
所有随机性都来自固定种子，重复运行得到完全相同的文件。
"""

from __future__ import annotations

import json
import random
from datetime import datetime, timedelta, timezone
from importlib.metadata import version
from pathlib import Path

import fsrs.scheduler as fsrs_scheduler
from fsrs import Card, Rating, Scheduler
from fsrs.scheduler import (
    DEFAULT_PARAMETERS,
    LOWER_BOUNDS_PARAMETERS,
    UPPER_BOUNDS_PARAMETERS,
)

PY_FSRS_VERSION = "6.3.2"
SEED = 20261021
START = datetime(2026, 1, 1, tzinfo=timezone.utc)
OUTPUT = (
    Path(__file__).resolve().parents[2]
    / "backend/src/test/resources/fsrs"
    / f"py-fsrs-{PY_FSRS_VERSION}-cases.json"
)


class RandomRecorder:
    """替换 fsrs.scheduler.random：从固定种子取随机数，并记录每次取到的值。"""

    def __init__(self, seed: int) -> None:
        self._rng = random.Random(seed)
        self.drawn: list[float] = []

    def __call__(self) -> float:
        value = self._rng.random()
        self.drawn.append(value)
        return value

    def take(self) -> list[float]:
        values, self.drawn = self.drawn, []
        return values


def perturbed_parameters(rng: random.Random) -> tuple[float, ...]:
    """在默认参数上做 ±20% 扰动，并限制在合法范围内，用来覆盖非默认参数。"""
    result = []
    for default, low, high in zip(
        DEFAULT_PARAMETERS, LOWER_BOUNDS_PARAMETERS, UPPER_BOUNDS_PARAMETERS
    ):
        value = default * rng.uniform(0.8, 1.2)
        result.append(round(min(max(value, low), high), 4))
    return tuple(result)


def minutes(*values: int) -> tuple[timedelta, ...]:
    return tuple(timedelta(minutes=v) for v in values)


def build_configs(rng: random.Random) -> dict[str, dict]:
    """调度器配置。py_default 是 py-fsrs 默认值，project_default 是本项目默认值（无学习步）。"""
    base = {
        "parameters": DEFAULT_PARAMETERS,
        "desired_retention": 0.9,
        "learning_steps": minutes(1, 10),
        "relearning_steps": minutes(10),
        "maximum_interval": 36500,
        "enable_fuzzing": False,
    }
    empty_steps = {"learning_steps": (), "relearning_steps": ()}
    return {
        "py_default": base,
        "project_default": {**base, **empty_steps},
        "one_step": {**base, "learning_steps": minutes(10), "relearning_steps": minutes(10)},
        "three_steps": {
            **base,
            "learning_steps": minutes(1, 10, 60),
            "relearning_steps": minutes(10, 60),
        },
        "retention_080": {**base, **empty_steps, "desired_retention": 0.8},
        "retention_095": {**base, **empty_steps, "desired_retention": 0.95},
        "max_interval_30": {**base, "maximum_interval": 30},
        "custom_parameters": {**base, **empty_steps, "parameters": perturbed_parameters(rng)},
        "py_default_fuzz": {**base, "enable_fuzzing": True},
        "project_default_fuzz": {**base, **empty_steps, "enable_fuzzing": True},
        # 目标保持率 0.9 时，首次复习的原始间隔 ≈ 初始稳定性。设成 x.5 正好落在取整的分界上，
        # 用来区分 Python round()（四舍六入五成双）和四舍五入。放在最后，不影响前面用例的随机序列。
        "half_intervals": {**base, **empty_steps, "parameters": (1.5, 2.5, 3.5, 4.5) + DEFAULT_PARAMETERS[4:]},
        # 以天为单位的学习步和重学步：超过 2.5 天，用来验证 fuzz 只作用于 REVIEW 状态，不作用于学习步、重学步
        "long_steps_fuzz": {
            **base,
            "learning_steps": (timedelta(days=1), timedelta(days=3)),
            "relearning_steps": (timedelta(days=3),),
            "enable_fuzzing": True,
        },
    }


def iso(dt: datetime | None) -> str | None:
    """统一输出带 Z 的 UTC 时间，精确到微秒，方便 Java 的 Instant.parse 读取。"""
    if dt is None:
        return None
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def card_to_json(card: Card) -> dict:
    return {
        "state": card.state.value,
        "step": card.step,
        "stability": card.stability,
        "difficulty": card.difficulty,
        "due": iso(card.due),
        "last_review": iso(card.last_review),
    }


def scheduler_to_json(config: dict) -> dict:
    return {
        "parameters": list(config["parameters"]),
        "desired_retention": config["desired_retention"],
        "learning_steps_seconds": [int(s.total_seconds()) for s in config["learning_steps"]],
        "relearning_steps_seconds": [int(s.total_seconds()) for s in config["relearning_steps"]],
        "maximum_interval": config["maximum_interval"],
        "enable_fuzzing": config["enable_fuzzing"],
    }


# 复习时间策略：拿到上一次复习后的卡片，返回这次复习的时间（不早于上次复习）
def on_due(card: Card, rng: random.Random) -> datetime:
    return card.due


def random_time(card: Card, rng: random.Random) -> datetime:
    """随机地提前、按时、推迟或当天重复复习，覆盖短期稳定性、过期等分支。"""
    last = card.last_review
    interval = card.due - last
    kind = rng.random()
    if kind < 0.15:  # 当天再复习一次
        when = last + timedelta(minutes=rng.randint(1, 600))
    elif kind < 0.30:  # 提前复习
        when = last + interval * rng.uniform(0.1, 0.9)
    elif kind < 0.75:  # 按时或稍晚
        when = card.due + timedelta(hours=rng.uniform(0, 36))
    else:  # 明显推迟
        when = card.due + interval * rng.uniform(0.5, 5.0)
    when += timedelta(microseconds=rng.randint(0, 999_999))
    return max(when, last + timedelta(seconds=1))


def run_case(name, config_name, config, ratings, time_strategy, rng, recorder) -> dict:
    scheduler = Scheduler(**config)
    card = Card(card_id=1, due=START)
    initial = card_to_json(card)
    reviews = []
    for i, rating in enumerate(ratings):
        when = START if i == 0 else time_strategy(card, rng)
        retrievability = scheduler.get_card_retrievability(card, current_datetime=when)
        recorder.take()
        card, _ = scheduler.review_card(card=card, rating=rating, review_datetime=when)
        reviews.append(
            {
                "rating": rating.value,
                "review_datetime": iso(when),
                "retrievability_before": retrievability,
                "fuzz_randoms": recorder.take(),
                "expected": card_to_json(card),
            }
        )
    return {
        "name": name,
        "config": config_name,
        "scheduler": scheduler_to_json(config),
        "initial_card": initial,
        "reviews": reviews,
    }


A, H, G, E = Rating.Again, Rating.Hard, Rating.Good, Rating.Easy

# 人工设计的场景：每种配置都跑一遍
SCRIPTED = {
    "single_again": ([A], on_due),
    "single_hard": ([H], on_due),
    "single_good": ([G], on_due),
    "single_easy": ([E], on_due),
    "all_good": ([G] * 12, on_due),
    "all_again": ([A] * 8, on_due),
    "all_hard": ([H] * 10, on_due),
    "all_easy": ([E] * 8, on_due),
    "lapse_and_recover": ([G, G, G, G, G, A, G, G, G, G], on_due),
    "alternating": ([G, A, G, H, E, A, H, G] * 2, on_due),
}


def to_compact_json(output: dict) -> str:
    """每个用例的头部一行、每次复习一行：比全部缩进小一半，又能逐行阅读和比较差异。"""
    dump = lambda value: json.dumps(value, ensure_ascii=False)
    lines = ["{", f' "generator": {dump(output["generator"])},', ' "cases": [']
    for i, case in enumerate(output["cases"]):
        head = {k: v for k, v in case.items() if k != "reviews"}
        lines.append(f"  {dump(head)[:-1]}, \"reviews\": [")
        lines.append(",\n".join(f"   {dump(r)}" for r in case["reviews"]))
        lines.append("  ]}" + ("," if i < len(output["cases"]) - 1 else ""))
    lines += [" ]", "}"]
    return "\n".join(lines) + "\n"


def main() -> None:
    rng = random.Random(SEED)
    recorder = RandomRecorder(SEED + 1)
    fsrs_scheduler.random = recorder  # py-fsrs 在 fuzz 时调用的随机函数

    configs = build_configs(rng)
    cases = []
    for config_name, config in configs.items():
        for scenario, (ratings, strategy) in SCRIPTED.items():
            cases.append(
                run_case(f"{config_name}/{scenario}", config_name, config, ratings, strategy, rng, recorder)
            )
        for n in range(10):
            ratings = rng.choices([A, H, G, E], weights=[2, 2, 5, 1], k=rng.randint(10, 20))
            cases.append(
                run_case(f"{config_name}/random_{n:02d}", config_name, config, ratings, random_time, rng, recorder)
            )

    output = {
        "generator": {
            "py_fsrs_version": version("fsrs"),
            "seed": SEED,
            "script": "tools/fsrs-testgen/generate.py",
        },
        "cases": cases,
    }
    assert output["generator"]["py_fsrs_version"] == PY_FSRS_VERSION, "py-fsrs 版本不符"
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(to_compact_json(output), encoding="utf-8", newline="\n")
    total_reviews = sum(len(c["reviews"]) for c in cases)
    fuzz_draws = sum(len(r["fuzz_randoms"]) for c in cases for r in c["reviews"])
    print(f"{len(cases)} 个用例，{total_reviews} 次复习，{fuzz_draws} 个 fuzz 随机数 -> {OUTPUT}")


if __name__ == "__main__":
    main()
