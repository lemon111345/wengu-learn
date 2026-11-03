<script setup lang="ts">
// 知识点列表（ui.md 5.9）：复选框、标题、类型、掌握、下次复习。
// 点表头排序：“知识点”回到学习顺序；“掌握”“下次复习”第一次升序，再点反向。排序由后端完成（决策 030）。
// 点标题在右侧打开详情，当前行用 --wg-surface 底色
import type { LibraryItem, LibrarySort, SortDirection } from '@/api/knowledgePoints'
import { masteryLabels, typeLabels } from '@/utils/labels'
import MasteryDot from './MasteryDot.vue'
import { nextReviewText } from './format'

const props = defineProps<{
  items: LibraryItem[]
  selectedId: number | null
  checked: Set<number>
  sort: LibrarySort
  direction: SortDirection
}>()

const emit = defineEmits<{
  select: [id: number]
  toggle: [id: number, checked: boolean]
  sort: [sort: LibrarySort]
}>()

const columns: { sort: LibrarySort; label: string }[] = [
  { sort: 'MASTERY', label: '掌握' },
  { sort: 'NEXT_REVIEW', label: '下次复习' },
]

function ariaSort(sort: LibrarySort) {
  if (props.sort !== sort) {
    return 'none'
  }
  return sort === 'ORDER' || props.direction === 'ASC' ? 'ascending' : 'descending'
}
</script>

<template>
  <!-- 宽表格在自己的容器内横向滚动，页面本身不横向滚动（ui.md 4.3） -->
  <section class="table-wrap">
    <table>
      <colgroup>
        <col class="col-check" />
        <col />
        <col class="col-type" />
        <col class="col-mastery" />
        <col class="col-next" />
      </colgroup>
      <thead>
        <tr>
          <th scope="col"><span class="visually-hidden">选择</span></th>
          <th scope="col" :aria-sort="ariaSort('ORDER')">
            <button type="button" class="head" :class="{ on: sort === 'ORDER' }" @click="emit('sort', 'ORDER')">
              知识点（按学习顺序）
            </button>
          </th>
          <th scope="col"><span class="head static">类型</span></th>
          <th v-for="c in columns" :key="c.sort" scope="col" :aria-sort="ariaSort(c.sort)">
            <button type="button" class="head" :class="{ on: sort === c.sort }" @click="emit('sort', c.sort)">
              {{ c.label }}<span v-if="sort === c.sort" aria-hidden="true">{{ direction === 'ASC' ? '↑' : '↓' }}</span>
            </button>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id" :class="{ current: item.id === selectedId }">
          <td class="check">
            <el-checkbox
              :model-value="checked.has(item.id)"
              :aria-label="`选择：${item.title}`"
              @change="(v: string | number | boolean) => emit('toggle', item.id, v === true)"
            />
          </td>
          <td class="title-cell">
            <button
              type="button"
              class="title"
              :aria-current="item.id === selectedId ? 'true' : undefined"
              @click="emit('select', item.id)"
            >
              {{ item.title }}
            </button>
          </td>
          <td class="muted small">{{ typeLabels[item.type] }}</td>
          <td>
            <span class="mastery"><MasteryDot :mastery="item.mastery" />{{ masteryLabels[item.mastery] }}</span>
          </td>
          <td :class="{ warn: nextReviewText(item).warn }">{{ nextReviewText(item).text }}</td>
        </tr>
      </tbody>
    </table>
  </section>
</template>

<style scoped>
.table-wrap {
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
  overflow-x: auto;
}

/* 列宽取自 mockups/12-library.dc.html：44px、弹性、96px、76px、100px，最小 640px */
table {
  width: 100%;
  min-width: 640px;
  border-collapse: collapse;
  table-layout: fixed;
}

.col-check {
  width: 44px;
}

.col-type {
  width: 96px;
}

.col-mastery {
  width: 76px;
}

.col-next {
  width: 100px;
}

th {
  height: 44px;
  padding: 0 8px 0 0;
  border-bottom: 1px solid var(--wg-border);
  text-align: left;
  font-weight: 400;
}

.head {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 44px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--wg-text-muted);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.head.static {
  cursor: default;
}

.head.on {
  color: var(--wg-text);
  font-weight: 700;
}

td {
  height: 52px;
  padding: 0 8px 0 0;
  border-bottom: 1px solid var(--wg-tag-bg);
  font-size: 14px;
}

tbody tr:last-child td {
  border-bottom: none;
}

tr.current td {
  background: var(--wg-surface);
}

.check {
  padding: 0;
  text-align: center;
}

.title {
  display: block;
  width: 100%;
  min-height: 44px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--wg-text);
  font: inherit;
  font-size: 15px;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.title:hover {
  color: var(--wg-primary);
}

tr.current .title {
  font-weight: 700;
}

.muted {
  color: var(--wg-text-muted);
}

.small {
  font-size: 13px;
}

.mastery {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.warn {
  color: var(--wg-warn);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
