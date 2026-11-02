<script setup lang="ts">
// 快捷键列表弹窗：居中，Esc 关闭（ui.md 第 7 节）
import { shortcutHelpOpen, shortcutList } from './list'
import { useShortcut } from './useShortcut'

useShortcut('?', () => (shortcutHelpOpen.value = true))
</script>

<template>
  <el-dialog v-model="shortcutHelpOpen" title="快捷键" width="440" align-center>
    <dl class="list">
      <div v-for="entry in shortcutList" :key="entry.label" class="row">
        <dt class="keys">
          <template v-for="(key, i) in entry.keys" :key="key">
            <span v-if="i > 0" class="sep">+</span>
            <kbd>{{ key }}</kbd>
          </template>
        </dt>
        <dd>{{ entry.label }}</dd>
      </div>
    </dl>
    <p class="note">焦点在输入框中时，单键快捷键不生效。</p>
  </el-dialog>
</template>

<style scoped>
.list {
  margin: 0;
}

.row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 0;
  border-bottom: 1px solid var(--wg-border);
}

.keys {
  flex: none;
  width: 140px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.sep {
  color: var(--wg-text-muted);
  font-size: 12px;
}

dd {
  margin: 0;
  font-size: 14px;
}

.note {
  margin-top: 12px;
  color: var(--wg-text-muted);
  font-size: 13px;
}
</style>
