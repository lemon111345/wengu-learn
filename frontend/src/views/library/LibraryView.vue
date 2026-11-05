<script setup lang="ts">
// 知识库（ui.md 5.9，决策 031）：筛选、排序、列表、右侧详情、批量操作。
// 筛选和排序交给后端（决策 030），每次切换重新取列表；任何操作完成后也重新取列表和详情
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError } from '@/api/http'
import {
  deleteKnowledgePoints,
  fetchLibrary,
  mergeKnowledgePoints,
  restoreKnowledgePoints,
  suspendKnowledgePoints,
  type LibrarySort,
  type LibraryView,
  type Mastery,
  type SortDirection,
} from '@/api/knowledgePoints'
import type { KnowledgePointType } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import { modKeyLabel, useShortcut } from '@/shortcuts/useShortcut'
import DetailPanel from './DetailPanel.vue'
import KnowledgeTable from './KnowledgeTable.vue'
import LibraryFilters from './LibraryFilters.vue'
import MergeDialog from './MergeDialog.vue'
import UndoToast from './UndoToast.vue'

const router = useRouter()
const route = useRoute()

const mastery = ref<Mastery | null>(null)
const type = ref<KnowledgePointType | null>(null)
const sort = ref<LibrarySort>('ORDER')
const direction = ref<SortDirection>('ASC')

const library = ref<LibraryView | null>(null)
const loadError = ref<string | null>(null)
const selectedId = ref<number | null>(null)
/**
 * 勾选的知识点：id → 标题，按勾选顺序。切换筛选后勾选保留，
 * 勾选的可能不在当前列表中，合并弹窗要显示它们的标题，所以记下来
 */
const checked = ref<Map<number, string>>(new Map())
const checkedIds = computed(() => new Set(checked.value.keys()))
const busy = ref(false)
const mergeOpen = ref(false)
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
  const next = new Map(checked.value)
  const item = library.value?.items.find((i) => i.id === id)
  if (on && item) {
    next.set(id, item.title)
  } else {
    next.delete(id)
  }
  checked.value = next
}

/**
 * 表头全选（#78）：只作用于当前显示的行（筛选和搜索之后），在别的筛选下勾选的不动，
 * 和“切换筛选后勾选保留”一致。全选是加进去，取消是只移出当前显示的这些
 */
function onToggleAll(on: boolean) {
  const next = new Map(checked.value)
  for (const item of visibleItems.value) {
    if (on) {
      next.set(item.id, item.title)
    } else {
      next.delete(item.id)
    }
  }
  checked.value = next
}

function uncheck(ids: number[]) {
  const next = new Map(checked.value)
  ids.forEach((id) => next.delete(id))
  checked.value = next
}

/** 批量操作改到了正在看的知识点时，详情也重新取 */
function reloadPanelIf(ids: number[]) {
  if (selectedId.value !== null && ids.includes(selectedId.value)) {
    void panel.value?.reload()
  }
}

async function suspendChecked() {
  const ids = [...checked.value.keys()]
  if (busy.value || ids.length === 0) {
    return
  }
  busy.value = true
  try {
    await suspendKnowledgePoints(ids)
    ElMessage.success(`已暂停 ${ids.length} 个知识点`)
    uncheck(ids)
    reloadPanelIf(ids)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '没有暂停成功，请重试')
  } finally {
    busy.value = false
  }
}

async function merge(primaryId: number) {
  const ids = [...checked.value.keys()]
  const mergedIds = ids.filter((id) => id !== primaryId)
  busy.value = true
  try {
    await mergeKnowledgePoints(primaryId, mergedIds)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '没有合并成功，请重试')
    busy.value = false
    return
  }
  busy.value = false
  mergeOpen.value = false
  ElMessage.success(`已合并 ${ids.length} 个知识点`)
  uncheck(ids)
  // 合并后打开主知识点，看到并过来的来源和学习记录
  if (selectedId.value === primaryId) {
    void panel.value?.reload()
  } else if (await confirmDiscard()) {
    selectedId.value = primaryId
  }
  await load()
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
  uncheck(ids)
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

// 搜索（ui.md 第 6 节、决策 031）：M1 在前端按标题过滤当前列表，叠加在两种筛选之上，不区分大小写
const search = ref('')
const searchInput = ref<{ focus: () => void } | null>(null)

const visibleItems = computed(() => {
  const items = library.value?.items ?? []
  const keyword = search.value.trim().toLowerCase()
  return keyword === '' ? items : items.filter((i) => i.title.toLowerCase().includes(keyword))
})

const filtered = computed(() => mastery.value !== null || type.value !== null || search.value.trim() !== '')

function clearFilters() {
  mastery.value = null
  type.value = null
  search.value = ''
}

// Ctrl+K 聚焦搜索框；在其他页面按 Ctrl+K 时，AppLayout 带上 ?search 跳过来
useShortcut('Mod+k', () => searchInput.value?.focus())

onMounted(async () => {
  if (route.query.search !== undefined) {
    await nextTick()
    searchInput.value?.focus()
    void router.replace({ query: {} })
  }
})
</script>

<template>
  <PageHeader title="知识库">
    <el-input
      ref="searchInput"
      v-model="search"
      class="search"
      placeholder="搜索知识点"
      aria-label="搜索知识点"
      clearable
    >
      <!-- 不用 type="search"：浏览器会再加一个清除按钮。输入后隐藏快捷键提示，给清除按钮让位 -->
      <template v-if="search === ''" #suffix>
        <span class="search-keys" aria-hidden="true"><kbd>{{ modKeyLabel }}</kbd><kbd>K</kbd></span>
      </template>
    </el-input>
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
      <!-- 批量操作条：勾选后出现（ui.md 5.9） -->
      <div v-if="checked.size > 0" class="batch-bar">
        <span class="batch-count">已选 {{ checked.size }} 个</span>
        <div class="batch-actions">
          <button type="button" class="batch-btn" :disabled="checked.size < 2 || busy" @click="mergeOpen = true">
            合并
          </button>
          <button type="button" class="batch-btn" :disabled="busy" @click="suspendChecked">暂停复习</button>
          <button type="button" class="batch-btn danger" :disabled="busy" @click="remove([...checked.keys()])">
            删除
          </button>
          <button type="button" class="batch-clear" @click="checked = new Map()">取消选择</button>
        </div>
      </div>
      <KnowledgeTable
        v-if="visibleItems.length > 0"
        :items="visibleItems"
        :selected-id="selectedId"
        :checked="checkedIds"
        :sort="sort"
        :direction="direction"
        @select="select"
        @toggle="onToggle"
        @toggle-all="onToggleAll"
        @sort="onSort"
      />
      <section v-else class="panel empty">
        <p>没有符合条件的知识点。</p>
        <el-button @click="clearFilters">清除筛选</el-button>
      </section>
      <p class="foot">
        显示 {{ visibleItems.length }} 个，共 {{ library.total }} 个<template v-if="filtered">（已筛选）</template>
      </p>
    </main>
    <div v-if="selectedId !== null" class="side">
      <DetailPanel ref="panel" :id="selectedId" @changed="load" @delete="(id) => remove([id])" />
    </div>
  </div>
  <MergeDialog
    v-model="mergeOpen"
    :items="[...checked].map(([id, title]) => ({ id, title }))"
    :busy="busy"
    @confirm="merge"
  />
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

.search {
  width: 240px;
}

.search-keys {
  display: inline-flex;
  gap: 2px;
}

/* 批量操作条：主色浅底（ui.md 5.9），按钮高 40 */
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 8px 8px 18px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-primary-soft);
}

.batch-count {
  color: var(--wg-primary);
  font-size: 14px;
  font-weight: 700;
}

.batch-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.batch-btn {
  min-height: 40px;
  padding: 0 14px;
  border: 1px solid var(--wg-primary-line);
  border-radius: var(--wg-radius-control);
  background: var(--wg-bg);
  color: var(--wg-text);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.batch-btn.danger {
  color: var(--wg-warn);
}

.batch-btn:disabled {
  color: var(--wg-text-muted);
  opacity: 0.6;
  cursor: default;
}

.batch-clear {
  min-height: 40px;
  padding: 0 10px;
  border: none;
  background: transparent;
  color: var(--wg-text-muted);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
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
