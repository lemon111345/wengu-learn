<script setup lang="ts">
// 列表上方的筛选（ui.md 5.9）：左边是掌握程度的筛选按钮，带色点和数量；右边是类型下拉框。两者同时生效。
// 数量由后端按类型筛选算好，切换类型时跟着变
import { computed } from 'vue'
import type { Mastery } from '@/api/knowledgePoints'
import type { KnowledgePointType } from '@/api/types'
import { masteryLabels, masteryOrder, typeLabels } from '@/utils/labels'
import MasteryDot from './MasteryDot.vue'

const props = defineProps<{ counts: Record<Mastery, number> | null }>()
const mastery = defineModel<Mastery | null>('mastery', { required: true })
const type = defineModel<KnowledgePointType | null>('type', { required: true })

const allCount = computed(() =>
  props.counts ? masteryOrder.reduce((sum, m) => sum + props.counts![m], 0) : null,
)

const typeOptions = Object.entries(typeLabels) as [KnowledgePointType, string][]

// el-select 不能用 null 作为选项的值，“全部类型”用空字符串表示
const typeValue = computed({
  get: () => type.value ?? '',
  set: (value: KnowledgePointType | '') => (type.value = value === '' ? null : value),
})
</script>

<template>
  <div class="filters">
    <div class="chips" role="group" aria-label="按掌握程度筛选">
      <button type="button" class="chip" :class="{ on: mastery === null }" :aria-pressed="mastery === null" @click="mastery = null">
        <span class="all-dot" aria-hidden="true"></span>全部<span class="n">{{ allCount ?? '' }}</span>
      </button>
      <button
        v-for="m in masteryOrder"
        :key="m"
        type="button"
        class="chip"
        :class="{ on: mastery === m }"
        :aria-pressed="mastery === m"
        @click="mastery = m"
      >
        <MasteryDot :mastery="m" />{{ masteryLabels[m] }}<span class="n">{{ counts?.[m] ?? '' }}</span>
      </button>
    </div>
    <!-- 值为空字符串时 el-select 当作没有选择，显示占位文字，所以占位文字也写“全部类型” -->
    <el-select
      v-model="typeValue"
      class="type-select"
      :class="{ on: type !== null }"
      placeholder="全部类型"
      aria-label="按类型筛选"
    >
      <el-option value="" label="全部类型" />
      <el-option v-for="[value, label] in typeOptions" :key="value" :value="value" :label="label" />
    </el-select>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 筛选按钮：胶囊形，高 40；选中时描边和底色变为主色系（ui.md 3） */
.chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 40px;
  padding: 0 14px;
  border: 1px solid var(--wg-border);
  border-radius: 999px;
  background: var(--wg-bg);
  color: var(--wg-text);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.chip:hover {
  border-color: var(--wg-border-strong);
}

.chip.on {
  border-color: var(--wg-primary-line);
  background: var(--wg-primary-soft);
  font-weight: 700;
}

.n {
  color: var(--wg-text-muted);
  font-weight: 400;
}

.all-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1.5px var(--wg-text);
}

.type-select {
  width: 140px;
}

.type-select :deep(.el-select__wrapper) {
  min-height: 40px;
  border-radius: 999px;
  font-size: 14px;
}

/* 类型筛选生效时：主色描边和浅底（ui.md 5.9） */
.type-select.on :deep(.el-select__wrapper) {
  background: var(--wg-primary-soft);
  box-shadow: 0 0 0 1px var(--wg-primary-line) inset;
}
</style>
