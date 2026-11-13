<script setup lang="ts">
// 答法速查（ui.md 5.13，#112）：学过的知识点各一条，带最近一次学习的面试参考答法。只读。
// 顺序由后端决定；搜索在前端过滤，和知识库一样（一次全部取回）
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/http'
import { fetchAnswers, type AnswerSummary } from '@/api/answers'
import PageHeader from '@/components/PageHeader.vue'
import { formatMonthDay } from '@/utils/date'
import { ratingColors, ratingLabels } from '@/utils/labels'
import ReferenceAnswer from '@/views/history/ReferenceAnswer.vue'

const router = useRouter()

const items = ref<AnswerSummary[] | null>(null)
const loadError = ref<string | null>(null)
const search = ref('')
/** 今天要复习、用户点了“仍然展开”的 */
const revealed = reactive(new Set<number>())

async function load() {
  loadError.value = null
  try {
    items.value = await fetchAnswers()
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '答法没有加载出来，请重试'
  }
}

onMounted(load)

const shown = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  const all = items.value ?? []
  return keyword === ''
    ? all
    : all.filter(
        (i) => i.kpTitle.toLowerCase().includes(keyword) || (i.referenceAnswer ?? '').toLowerCase().includes(keyword),
      )
})

const countText = computed(() =>
  search.value.trim() === '' ? `共 ${shown.value.length} 题 · 最近学的在前` : `找到 ${shown.value.length} 题`,
)

function folded(item: AnswerSummary) {
  return item.dueToday && !revealed.has(item.kpId)
}
</script>

<template>
  <PageHeader title="答法速查">
    <el-input v-model="search" class="search" placeholder="搜标题和答法" aria-label="搜索答法" clearable />
  </PageHeader>
  <div class="wg-page-body">
    <div class="answers">
      <section v-if="loadError && !items" class="state" role="alert">
        <p>{{ loadError }}</p>
        <el-button @click="load">重试</el-button>
      </section>
      <el-skeleton v-else-if="!items" :rows="8" animated aria-busy="true" />
      <section v-else-if="items.length === 0" class="state">
        <p>学完一个知识点后，它的面试参考答法会出现在这里。</p>
        <el-button @click="router.push('/')">去今天的学习</el-button>
      </section>
      <template v-else>
        <p class="count">{{ countText }}</p>
        <p v-if="shown.length === 0" class="state">没有找到相关的答法</p>
        <ul v-else class="list">
          <li v-for="item in shown" :key="item.kpId" class="item">
            <div class="head">
              <h2>{{ item.kpTitle }}</h2>
              <span class="meta">
                <template v-if="item.rating">
                  <span class="dot" :style="{ background: ratingColors[item.rating] }" />{{ ratingLabels[item.rating] }}
                  <span aria-hidden="true">·</span>
                </template>
                {{ formatMonthDay(item.studiedDate) }}
              </span>
            </div>
            <!-- 今天要复习的先折叠，免得复习前先看到答案 -->
            <p v-if="folded(item)" class="folded">
              今天要复习，先回忆再看
              <button type="button" class="link" @click="revealed.add(item.kpId)">仍然展开</button>
            </p>
            <ReferenceAnswer v-else :session-id="item.sessionId" :answer="item.referenceAnswer" />
          </li>
        </ul>
      </template>
    </div>
  </div>
</template>

<style scoped>
.search {
  width: 240px;
}

.answers {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 760px;
  margin: 0 auto;
  padding-top: 32px;
}

.count,
.state {
  color: var(--wg-text-muted);
  font-size: 14px;
}

.state {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 条与条之间用 1px 分隔线（ui.md 5.13） */
.item {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 20px 0;
  border-top: 1px solid var(--wg-border);
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 16px;
}

/* 知识点标题用宋体（ui.md 2.2） */
h2 {
  font-family: var(--wg-font-serif);
  font-size: 17px;
  font-weight: 700;
  line-height: 1.5;
}

.meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--wg-text-muted);
  font-size: 14px;
  white-space: nowrap;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.folded {
  padding: 14px 18px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
  color: var(--wg-text-muted);
  font-size: 14px;
}

.link {
  padding: 0 4px;
  border: none;
  background: transparent;
  color: var(--wg-primary);
  font: inherit;
  cursor: pointer;
}

.link:hover {
  color: var(--wg-primary-hover);
}

@media (max-width: 600px) {
  .search {
    width: 100%;
  }
}
</style>
