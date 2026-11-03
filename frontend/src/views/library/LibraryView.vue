<script setup lang="ts">
// 知识库（ui.md 5.9，决策 031）：筛选、排序、列表、右侧详情、批量操作。
// 筛选和排序交给后端（决策 030），每次切换重新取列表；任何操作完成后也重新取列表和详情
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import {
  deleteKnowledgePoints,
  fetchLibrary,
  restoreKnowledgePoints,
  type LibrarySort,
  type LibraryView,
  type Mastery,
  type SortDirection,
} from '@/api/knowledgePoints'
import type { KnowledgePointType } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import DetailPanel from './DetailPanel.vue'
import KnowledgeTable from './KnowledgeTable.vue'
import LibraryFilters from './LibraryFilters.vue'
import UndoToast from './UndoToast.vue'

const router = useRouter()

const mastery = ref<Mastery | null>(null)
const type = ref<KnowledgePointType | null>(null)
const sort = ref<LibrarySort>('ORDER')
const direction = ref<SortDirection>('ASC')

const library = ref<LibraryView | null>(null)
const loadError = ref<string | null>(null)
const selectedId = ref<number | null>(null)
const checked = ref<Set<number>>(new Set())
const panel = ref<InstanceType<typeof DetailPanel> | null>(null)
/** 删除后的撤销提示，带上删除的 id */
const undoNotice = ref<{ text: string; ids: number[] } | null>(null)

// 连续切换筛选时，只用最后一次请求的结果，先发后到的旧结果丢掉
let latestRequest = 0

async function load() {
  const request = ++latestRequest
  try {
    const result = await fetchLibrary({
      mastery: mastery.value,
      type: type.value,
      sort: sort.value,
      direction: direction.value,
    })
    if (request !== latestRequest) {
      return
    }
    library.value = result
    loadError.value = null
    // 原来选中的还在列表中就保持，否则选中第一个；详情有没保存的修改时不换，免得筛选一下修改就没了
    if (!result.items.some((i) => i.id === selectedId.value) && !panel.value?.dirty) {
      selectedId.value = result.items[0]?.id ?? null
    }
  } catch (e) {
    if (request === latestRequest) {
      loadError.value = e instanceof ApiError ? e.message : '知识库没有加载出来，请重试'
    }
  }
}

onMounted(load)
watch([mastery, type, sort, direction], load)

/** 点表头：学习顺序不分方向；“掌握”“下次复习”第一次升序，再点同一列反向 */
function onSort(next: LibrarySort) {
  if (next === sort.value && next !== 'ORDER') {
    direction.value = direction.value === 'ASC' ? 'DESC' : 'ASC'
  } else {
    sort.value = next
    direction.value = 'ASC'
  }
}

function onToggle(id: number, on: boolean) {
  const next = new Set(checked.value)
  if (on) {
    next.add(id)
  } else {
    next.delete(id)
  }
  checked.value = next
}

/** 详情有没保存的修改时问一下；返回 true 表示可以继续 */
async function confirmDiscard(): Promise<boolean> {
  if (!panel.value?.dirty) {
    return true
  }
  try {
    await ElMessageBox.confirm('详情中的修改还没有保存，离开后就没有了。', '放弃修改？', {
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

async function select(id: number) {
  if (id !== selectedId.value && (await confirmDiscard())) {
    selectedId.value = id
  }
}

onBeforeRouteLeave(confirmDiscard)

/** 删除（软删除），底部提示几秒钟的“撤销”。详情中的删除和批量删除都走这里 */
async function remove(ids: number[]) {
  try {
    await deleteKnowledgePoints(ids)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '没有删除成功，请重试')
    return
  }
  undoNotice.value = { text: ids.length === 1 ? '已删除' : `已删除 ${ids.length} 个知识点`, ids }
  const next = new Set(checked.value)
  ids.forEach((id) => next.delete(id))
  checked.value = next
  if (selectedId.value !== null && ids.includes(selectedId.value)) {
    // 删掉的是正在看的：重新取列表后选中第一个
    selectedId.value = null
  }
  await load()
}

async function undo() {
  const notice = undoNotice.value
  if (!notice) {
    return
  }
  undoNotice.value = null
  try {
    await restoreKnowledgePoints(notice.ids)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '没有撤销成功，请重试')
    return
  }
  // 撤销后回到删除的那一个
  selectedId.value = notice.ids[0]
  await load()
}

const filtered = computed(() => mastery.value !== null || type.value !== null)

function clearFilters() {
  mastery.value = null
  type.value = null
}
</script>

<template>
  <PageHeader title="知识库">
    <el-button type="primary" @click="router.push('/import')">导入资料</el-button>
  </PageHeader>
  <div v-if="loadError && !library" class="wg-page-body">
    <section class="panel load-error" role="alert">
      <p>{{ loadError }}</p>
      <el-button @click="load">重试</el-button>
    </section>
  </div>
  <!-- 加载中用骨架占位，不用全屏转圈（ui.md 第 7 节） -->
  <div v-else-if="!library" class="wg-page-body layout" aria-busy="true">
    <div class="main">
      <el-skeleton class="panel skeleton" :rows="8" animated />
    </div>
    <div class="side">
      <el-skeleton class="panel skeleton" :rows="6" animated />
    </div>
  </div>
  <div v-else-if="library.total === 0" class="wg-page-body">
    <section class="panel empty">
      <p>知识库还是空的。导入一份面试题，确认后的知识点会出现在这里。</p>
      <el-button type="primary" @click="router.push('/import')">导入资料</el-button>
    </section>
  </div>
  <div v-else class="wg-page-body layout">
    <main class="main">
      <LibraryFilters v-model:mastery="mastery" v-model:type="type" :counts="library.counts" />
      <KnowledgeTable
        v-if="library.items.length > 0"
        :items="library.items"
        :selected-id="selectedId"
        :checked="checked"
        :sort="sort"
        :direction="direction"
        @select="select"
        @toggle="onToggle"
        @sort="onSort"
      />
      <section v-else class="panel empty">
        <p>没有符合条件的知识点。</p>
        <el-button @click="clearFilters">清除筛选</el-button>
      </section>
      <p class="foot">
        显示 {{ library.items.length }} 个，共 {{ library.total }} 个<template v-if="filtered">（已筛选）</template>
      </p>
    </main>
    <div v-if="selectedId !== null" class="side">
      <DetailPanel ref="panel" :id="selectedId" @changed="load" @delete="(id) => remove([id])" />
    </div>
  </div>
  <UndoToast :notice="undoNotice" @undo="undo" @close="undoNotice = null" />
</template>

<style scoped>
.layout {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 28px;
}

.main {
  flex: 999 1 620px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 右侧详情 320～420（ui.md 4.1、mockup） */
.side {
  flex: 1 1 340px;
  max-width: 420px;
}

.panel {
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.skeleton {
  padding: 24px;
}

.load-error,
.empty {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 20px;
  padding: 24px 28px;
}

.foot {
  color: var(--wg-text-muted);
  font-size: 14px;
}

/* 窄屏：右侧详情换到列表下方，撑满宽度 */
@media (max-width: 980px) {
  .side {
    max-width: none;
  }
}
</style>
