<script setup lang="ts">
// 快捷键的完整列表（ui.md 第 6 节），和按 ? 弹出的列表读同一份数据
import { shortcutList } from '@/shortcuts/list'
import SettingsCard from './SettingsCard.vue'
</script>

<template>
  <SettingsCard title="快捷键">
    <dl class="list">
      <div v-for="entry in shortcutList" :key="entry.label" class="row">
        <dt class="label">{{ entry.label }}</dt>
        <dd class="keys">
          <template v-for="(key, i) in entry.keys" :key="key">
            <span v-if="i > 0" class="sep">+</span>
            <kbd>{{ key }}</kbd>
          </template>
        </dd>
      </div>
    </dl>
  </SettingsCard>
</template>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
}

.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  min-height: 32px;
  font-size: 14px;
}

.label {
  color: var(--wg-text-muted);
}

.keys {
  display: flex;
  flex: none;
  align-items: center;
  gap: 4px;
  margin: 0;
}

.sep {
  color: var(--wg-text-muted);
  font-size: 12px;
}
</style>
