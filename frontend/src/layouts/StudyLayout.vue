<script setup lang="ts">
// 学习模式（ui.md 4.2）：隐藏侧栏，只做眼前这一件事。
// 顶栏中间的当天进度和右边的“本次 X 分钟”由学习页的 issue（#23）加上
</script>

<template>
  <div class="study">
    <header class="study-header">
      <RouterLink to="/" class="back">← 今天</RouterLink>
    </header>
    <div class="study-column">
      <RouterView />
    </div>
  </div>
</template>

<style scoped>
.study {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.study-header {
  display: flex;
  align-items: center;
  min-height: 60px;
  padding: 10px 32px;
  border-bottom: 1px solid var(--wg-border);
}

.back {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--wg-text-muted);
}

/* 读和写都需要合适的行宽，留白是有意的 */
.study-column {
  flex: 1;
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
  padding: 32px 16px;
  display: flex;
  flex-direction: column;
}

@media (max-width: 600px) {
  .study-header {
    padding: 10px 16px;
  }
}
</style>
