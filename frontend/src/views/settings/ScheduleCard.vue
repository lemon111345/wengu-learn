<script setup lang="ts">
// 学习安排：每天学几个、新的一天从几点开始、时区（ui.md 5.10）。
// 改了就交给页面保存，这里只管显示；每天学几个停手 500 毫秒后再保存，连点几下只发一次请求
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import NumberStepper from '@/components/NumberStepper.vue'
import { DAILY_NEW_MAX, DAILY_NEW_MIN, type Settings } from '@/api/settings'
import { timezoneOptions } from '@/utils/timezone'
import SettingsCard from './SettingsCard.vue'

const props = defineProps<{ settings: Settings }>()
const emit = defineEmits<{ change: [changes: Partial<Settings>] }>()

const STEP_DELAY_MS = 500

// 每天学几个：点击后立刻显示新值，停手后再保存。保存失败时页面把设置改回原值，这里跟着变回去
const dailyNew = ref(props.settings.dailyNewLimit)
let timer: ReturnType<typeof setTimeout> | undefined
watch(
  () => props.settings.dailyNewLimit,
  (v) => (dailyNew.value = v),
)
function flushDailyNew() {
  clearTimeout(timer)
  timer = undefined
  if (dailyNew.value !== props.settings.dailyNewLimit) {
    emit('change', { dailyNewLimit: dailyNew.value })
  }
}
watch(dailyNew, () => {
  clearTimeout(timer)
  timer = setTimeout(flushDailyNew, STEP_DELAY_MS)
})
// 还没到 500 毫秒就离开页面时，立刻保存，不丢改动
onBeforeUnmount(() => {
  if (timer !== undefined) {
    flushDailyNew()
  }
})

const dailyNewHint = computed(() =>
  dailyNew.value === 0 ? '设为 0 时只复习，不学新的' : '包括复习。复习优先，复习多的时候新学会变少',
)

// 新的一天从几点开始：给凌晨 0、2、4、6 点。后端允许 0～23，存的是别的值时也列出来，不会显示成空白
const DAY_START_CHOICES = [0, 2, 4, 6]
const dayStartOptions = computed(() =>
  [...new Set([...DAY_START_CHOICES, props.settings.dayStartHour])]
    .sort((a, b) => a - b)
    .map((hour) => ({ value: hour, label: hour <= 6 ? `凌晨 ${hour} 点` : `${hour} 点` })),
)

// 时区：几百个选项，可以搜索显示名或 IANA 名（如输入 shanghai）
const allTimezones = computed(() => timezoneOptions(props.settings.timezone))
const timezoneQuery = ref('')
const filteredTimezones = computed(() => {
  const q = timezoneQuery.value.trim().toLowerCase()
  return q
    ? allTimezones.value.filter((o) => o.label.toLowerCase().includes(q) || o.id.toLowerCase().includes(q))
    : allTimezones.value
})
</script>

<template>
  <SettingsCard title="学习安排">
    <div class="row">
      <div class="text">
        <span class="name">每天学几个</span>
        <span class="hint">{{ dailyNewHint }}</span>
      </div>
      <NumberStepper v-model="dailyNew" :min="DAILY_NEW_MIN" :max="DAILY_NEW_MAX" label="每天学几个" />
    </div>

    <div class="row divided">
      <div class="text">
        <label class="name" for="settings-day-start">新的一天从几点开始</label>
        <span class="hint">在这之前学的，算前一天。熬夜学习的话可以调晚一点。</span>
      </div>
      <el-select
        id="settings-day-start"
        class="day-start"
        :model-value="settings.dayStartHour"
        @update:model-value="(v: number) => emit('change', { dayStartHour: v })"
      >
        <el-option v-for="o in dayStartOptions" :key="o.value" :value="o.value" :label="o.label" />
      </el-select>
    </div>

    <div class="row divided">
      <div class="text">
        <label class="name" for="settings-timezone">时区</label>
      </div>
      <el-select
        id="settings-timezone"
        class="timezone"
        filterable
        :filter-method="(q: string) => (timezoneQuery = q)"
        :model-value="settings.timezone"
        @update:model-value="(v: string) => emit('change', { timezone: v })"
        @visible-change="(open: boolean) => open || (timezoneQuery = '')"
      >
        <el-option v-for="o in filteredTimezones" :key="o.id" :value="o.id" :label="o.label">
          <span class="tz-label">{{ o.label }}</span>
          <span class="tz-id">{{ o.id }}</span>
        </el-option>
      </el-select>
    </div>
  </SettingsCard>
</template>

<style scoped>
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.divided {
  padding-top: 20px;
  border-top: 1px solid var(--wg-tag-bg);
}

.text {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 300px;
}

.name {
  font-size: 15px;
  font-weight: 700;
}

.hint {
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.6;
}

.day-start {
  width: 120px;
}

.timezone {
  width: 220px;
}

.day-start :deep(.el-select__wrapper),
.timezone :deep(.el-select__wrapper) {
  min-height: 44px;
  font-size: 15px;
}

.tz-label {
  margin-right: 16px;
}

.tz-id {
  float: right;
  color: var(--wg-text-muted);
  font-size: 13px;
}
</style>
