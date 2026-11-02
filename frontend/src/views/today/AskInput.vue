<script setup lang="ts">
// “从一个问题开始”的输入框和按钮。功能由 #54 实现，现在先留好位置，输入框和按钮都禁用
defineProps<{ id: string }>()
</script>

<template>
  <div class="ask-input">
    <el-input :id="id" disabled placeholder="比如：面试被问到 G1 和 CMS 有什么区别，没答上来" />
    <el-tooltip content="即将开放" placement="top">
      <!-- 禁用的按钮不触发鼠标事件，提示要挂在外面一层 -->
      <span tabindex="0">
        <el-button disabled>开始学</el-button>
      </span>
    </el-tooltip>
  </div>
</template>

<style scoped>
.ask-input {
  flex: 1 1 240px;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.ask-input :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
}
</style>
