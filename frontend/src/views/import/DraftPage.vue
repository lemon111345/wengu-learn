<script setup lang="ts">
// 草稿 /drafts/:id（决策 028）：按状态显示。抽取中、失败时是导入的第 3 步（抽取进度）；
// 等你确认时是“确认知识点”（ui.md 5.8）；已确认时是结果摘要。
// 今天页的提示条、导入页的“最近导入”都链接到这里，刷新、离开后再回来都能接着看
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchDraft, retryDraft, type DraftView } from '@/api/drafts'
import { ApiError } from '@/api/http'
import PageHeader from '@/components/PageHeader.vue'
import ConfirmDraft from './ConfirmDraft.vue'
import ExtractionProgress from './ExtractionProgress.vue'
import ImportSidebar from './ImportSidebar.vue'
import ImportSteps from './ImportSteps.vue'

/** 抽取中每隔多久取一次草稿（决策 013：进度几十秒才变一次，轮询就够） */
const POLL_INTERVAL = 3000

const props = defineProps<{ id: string }>()

const draft = ref<DraftView | null>(null)
const loadError = ref<string | null>(null)
const retrying = ref(false)
/** 这次打开时在看进度：抽完后停在进度页，点“查看结果”再进入确认（ui.md 5.7） */
const watchedProgress = ref(false)
const viewingResult = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

const running = computed(() => draft.value?.batches.some((b) => b.status === 'PENDING' || b.status === 'RUNNING'))
const showProgress = computed(() => {
  const status = draft.value?.status
  return status === 'RUNNING' || status === 'FAILED' || (watchedProgress.value && !viewingResult.value)
})

async function load() {
  stopPolling()
  try {
    draft.value = await fetchDraft(Number(props.id))
    loadError.value = null
  } catch (e) {
    if (!draft.value) {
      loadError.value = e instanceof ApiError ? e.message : '没有加载出来，请重试'
    }
  }
  if (draft.value && draft.value.status !== 'READY' && draft.value.status !== 'CONFIRMED') {
    watchedProgress.value = true
  }
  if (running.value) {
    timer = setTimeout(load, POLL_INTERVAL)
  }
}

function stopPolling() {
  if (timer !== null) {
    clearTimeout(timer)
    timer = null
  }
}

async function retry() {
  retrying.value = true
  try {
    draft.value = await retryDraft(Number(props.id))
    void load()
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '没有开始重试，请再试一次'
  } finally {
    retrying.value = false
  }
}

// 从一份草稿跳到另一份（如“单独再抽”）时，组件不重建，重新开始
watch(
  () => props.id,
  () => {
    draft.value = null
    watchedProgress.value = false
    viewingResult.value = false
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(stopPolling)
</script>

<template>
  <div v-if="loadError && !draft" class="wg-page-body load-error" role="alert">
    <p>{{ loadError }}</p>
    <el-button @click="load">重试</el-button>
  </div>
  <el-skeleton v-else-if="!draft" class="wg-page-body" :rows="6" animated />
  <template v-else-if="showProgress">
    <PageHeader title="导入资料">
      <ImportSteps :current="3" />
    </PageHeader>
    <div class="wg-page-body layout">
      <main class="main">
        <ExtractionProgress :draft="draft" :retrying="retrying" @retry="retry" @view-result="viewingResult = true" />
        <p v-if="loadError" class="error" role="alert">{{ loadError }}</p>
      </main>
      <ImportSidebar />
    </div>
  </template>
  <ConfirmDraft v-else :draft="draft" @changed="load" />
</template>

<style scoped>
.layout {
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
  gap: 16px;
}

.error {
  color: var(--wg-warn);
  font-size: 14px;
}

.load-error {
  display: flex;
  align-items: center;
  gap: 16px;
}
</style>
