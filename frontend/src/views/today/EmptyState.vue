<script setup lang="ts">
// 今天页的空状态（ui.md 5.2，截图 04-today-empty.png）：还没有任何知识点时显示
import type { DraftSummary } from '@/api/drafts'
import AskInput from './AskInput.vue'
import DailyNewStepper from './DailyNewStepper.vue'
import PendingBanner from './PendingBanner.vue'

defineProps<{ drafts: DraftSummary[]; dailyNewLimit: number }>()
const emit = defineEmits<{ saved: [value: number] }>()

const steps = [
  { title: '导入资料', text: '粘贴面试题，或者上传 PDF、Markdown、Word 文档' },
  { title: '确认知识点', text: 'AI 拆好之后，你看一遍，去掉不想学的' },
  { title: '每天打开学', text: '按计划学新的、复习旧的，学完打个分' },
]
</script>

<template>
  <main class="main">
    <!-- 导入了还没确认时，空状态上也要提示 -->
    <PendingBanner :drafts="drafts" />
    <section class="hero">
      <div class="intro">
        <h1>先放进一些要学的东西</h1>
        <p>把面试题、笔记或文档导入进来，温故会把它们拆成一个个知识点，每天安排你学几个新的、复习几个旧的。</p>
      </div>
      <ol class="steps">
        <li v-for="(step, i) in steps" :key="step.title" class="step">
          <span class="step-no" :class="{ first: i === 0 }">{{ i + 1 }}</span>
          <span class="step-title">{{ step.title }}</span>
          <span class="step-text">{{ step.text }}</span>
        </li>
      </ol>
      <RouterLink to="/import" class="import el-button el-button--primary">导入资料<kbd>Enter</kbd></RouterLink>
    </section>
    <section class="ask">
      <div class="ask-intro">
        <h2>手上没有现成的资料？</h2>
        <p>从一个你想弄懂的问题开始也行。聊完后，聊到的知识点会整理出来，确认后加入复习。</p>
      </div>
      <div class="ask-box">
        <label for="ask-empty" class="visually-hidden">你想弄懂的问题</label>
        <AskInput id="ask-empty" />
      </div>
    </section>
  </main>
  <aside class="side">
    <section class="panel">
      <h2>每天学几个</h2>
      <DailyNewStepper :value="dailyNewLimit" align="start" @saved="emit('saved', $event)" />
      <p>包括新学和复习，复习优先。一个知识点大约要聊 10～30 分钟，按你每天有多少时间来定。之后随时可以改。</p>
    </section>
    <section class="panel">
      <h2>温故怎么安排复习</h2>
      <p>
        每学完一个知识点，你给自己打个分。记得越牢，下次出现得越晚：可能是明天，也可能是几周以后。每次复习都会换个角度问，不会让你背答案。
      </p>
    </section>
  </aside>
</template>

<style scoped>
.main {
  flex: 999 1 620px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.side {
  flex: 1 1 320px;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hero {
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding: 40px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.intro {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 620px;
}

h1 {
  font-size: 34px;
  line-height: 1.35;
}

.intro p {
  color: var(--wg-text-muted);
  font-size: 16px;
  line-height: 1.8;
}

.steps {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.step {
  flex: 1 1 200px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 20px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
}

.step-no {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--wg-border);
  color: var(--wg-text-muted);
  font-size: 14px;
  font-weight: 700;
}

/* 第一步是现在该做的 */
.step-no.first {
  background: var(--wg-primary);
  color: var(--wg-bg);
}

.step-title {
  font-size: 16px;
  font-weight: 700;
}

.step-text {
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.7;
}

.import {
  align-self: flex-start;
  height: 48px;
  padding: 0 24px;
  font-size: 16px;
}

.import kbd {
  margin-left: 12px;
}

.ask {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 24px 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.ask-intro {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ask-intro h2 {
  font-size: 17px;
}

.ask-intro p,
.panel p {
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.7;
}

.ask-box {
  display: flex;
  padding: 6px 6px 6px 14px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-card);
}

.panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 22px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.panel h2 {
  font-size: 16px;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

@media (max-width: 980px) {
  .side {
    max-width: none;
  }
}

@media (max-width: 600px) {
  .hero {
    padding: 24px 20px;
  }

  h1 {
    font-size: 28px;
  }
}
</style>
