<script setup lang="ts">
// 今天页（ui.md 5.2）：取计划和草稿列表，按状态显示
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchDrafts, type DraftSummary } from '@/api/drafts'
import { fetchTodayPlan, type TodayPlan } from '@/api/plan'
import PageHeader from '@/components/PageHeader.vue'
import { useShortcut } from '@/shortcuts/useShortcut'
import { formatStudyDate } from '@/utils/date'
import { ApiError } from '@/api/http'
import AskInput from './AskInput.vue'
import EmptyState from './EmptyState.vue'
import OverviewPanel from './OverviewPanel.vue'
import PendingBanner from './PendingBanner.vue'
import PlanList from './PlanList.vue'
import StudyTimePanel from './StudyTimePanel.vue'
import UpcomingPanel from './UpcomingPanel.vue'
import { nextItem } from './summary'

const router = useRouter()

const plan = ref<TodayPlan | null>(null)
const drafts = ref<DraftSummary[]>([])
// 计划取不到时的提示；为 null 表示没有出错
const loadError = ref<string | null>(null)

async function load() {
  loadError.value = null
  // 草稿列表只用于提示条，取不到时不影响计划的显示
  const [planResult, draftsResult] = await Promise.allSettled([fetchTodayPlan(), fetchDrafts()])
  if (planResult.status === 'fulfilled') {
    plan.value = planResult.value
  } else {
    const e = planResult.reason
    loadError.value = e instanceof ApiError ? e.message : '今天的计划没有加载出来，请重试'
  }
  drafts.value = draftsResult.status === 'fulfilled' ? draftsResult.value : []
}

onMounted(load)

// 一个知识点也没有时显示空状态；不能只看计划是否为空，见 TodayPlan.knowledgePointCount
const empty = computed(() => plan.value?.knowledgePointCount === 0)
const next = computed(() => (plan.value ? nextItem(plan.value) : null))

function start(kpId: number) {
  void router.push(`/study/${kpId}`)
}

// Enter：有下一个就开始学；空状态时去导入资料
useShortcut('Enter', () => {
  if (empty.value) {
    void router.push('/import')
  } else if (next.value) {
    start(next.value.kpId)
  }
})

// 每天学几个保存后，新知识的名额变了，重新取计划。取不到时保留原来的计划，不切到出错页面
function onDailyNewSaved() {
  void load()
}
</script>

<template>
  <PageHeader>
    <template #start>
      <span class="date">{{ plan ? formatStudyDate(plan.date) : '' }}</span>
    </template>
  </PageHeader>
  <div v-if="loadError && !plan" class="wg-page-body">
    <section class="load-error" role="alert">
      <p>{{ loadError }}</p>
      <el-button @click="load">重试</el-button>
    </section>
  </div>
  <!-- 加载中用骨架占位，不用全屏转圈（ui.md 第 7 节） -->
  <div v-else-if="!plan" class="wg-page-body today" aria-busy="true">
    <div class="main">
      <el-skeleton class="skeleton-panel" :rows="4" animated />
      <el-skeleton class="skeleton-panel" :rows="5" animated />
    </div>
    <div class="side">
      <el-skeleton class="skeleton-panel" :rows="5" animated />
    </div>
  </div>
  <div v-else-if="empty" class="wg-page-body today">
    <EmptyState :drafts="drafts" :daily-new-limit="plan.dailyNewLimit" @saved="onDailyNewSaved" />
  </div>
  <div v-else class="wg-page-body today">
    <main class="main">
      <OverviewPanel :plan="plan" @start="start" />
      <section class="ask">
        <label for="ask-start">从一个问题开始</label>
        <AskInput id="ask-start" />
      </section>
      <PendingBanner :drafts="drafts" />
      <div class="lists">
        <PlanList
          class="new-list"
          title="新学"
          :completed="plan.completed.filter((c) => c.firstReview)"
          :pending="plan.newItems"
          :next-kp-id="next?.kpId"
          empty-text="今天没有新知识"
        />
        <PlanList
          class="review-list"
          title="复习"
          :completed="plan.completed.filter((c) => !c.firstReview)"
          :pending="plan.reviews"
          :next-kp-id="next?.kpId"
          empty-text="今天没有到期的复习"
        />
      </div>
    </main>
    <aside class="side">
      <UpcomingPanel :days="plan.upcomingReviews" :daily-new-limit="plan.dailyNewLimit" @saved="onDailyNewSaved" />
      <StudyTimePanel
        :today-minutes="plan.studiedMinutesToday"
        :week-minutes="plan.studiedMinutesThisWeek"
        :streak="plan.currentStreak"
      />
    </aside>
  </div>
</template>

<style scoped>
.date {
  color: var(--wg-text-muted);
  font-size: 15px;
}

.today {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 28px;
  padding-top: 32px;
}

.main {
  flex: 999 1 620px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.side {
  flex: 1 1 320px;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.load-error {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
  padding: 24px 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.skeleton-panel {
  padding: 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.ask {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 14px;
  padding: 8px 8px 8px 20px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.ask label {
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
}

.lists {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 20px;
}

.new-list {
  flex: 1 1 320px;
}

.review-list {
  flex: 1.4 1 400px;
}

/* 窄屏：右侧栏换到主列下方，撑满宽度 */
@media (max-width: 980px) {
  .side {
    max-width: none;
  }
}

@media (max-width: 600px) {
  .today {
    padding-top: 20px;
  }

  .main {
    flex-basis: 100%;
  }

  .new-list,
  .review-list {
    flex-basis: 100%;
  }
}
</style>
