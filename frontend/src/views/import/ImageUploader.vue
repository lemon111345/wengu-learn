<script setup lang="ts">
// 导入页的“截图”（ui.md 5.7，决策 044）：选择或粘贴图片，提交识别，轮询结果，识别出的文字交给页面。
// 图片只在这个组件里，识别完就丢弃，只保留文字
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError } from '@/api/http'
import {
  fetchImageRecognition,
  IMAGE_ACCEPT,
  MAX_IMAGE_BYTES,
  MAX_IMAGE_TOTAL_BYTES,
  MAX_IMAGES,
  startImageRecognition,
  type ImageRecognitionView,
} from '@/api/sources'
import { modKeyLabel } from '@/shortcuts/useShortcut'

const emit = defineEmits<{ recognized: [text: string] }>()

const POLL_INTERVAL_MS = 2000
/** 后端重启后任务不会恢复，轮询不能一直等下去（决策 044） */
const POLL_LIMIT_MS = 5 * 60 * 1000

interface PickedImage {
  id: number
  file: File
  /** 缩略图的本地地址，不用时要释放 */
  url: string
}

const images = ref<PickedImage[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)
let nextId = 1
let unmounted = false

/** 这张图不能加入时的原因；可以加入时为 null */
function rejection(file: File, current: PickedImage[]): string | null {
  if (!IMAGE_ACCEPT.split(',').includes(file.type)) {
    return '只支持 PNG、JPG、WebP 格式的图片'
  }
  if (current.length >= MAX_IMAGES) {
    return `一次最多识别 ${MAX_IMAGES} 张图片`
  }
  if (file.size > MAX_IMAGE_BYTES) {
    return '每张图片不能超过 5 MB'
  }
  const total = current.reduce((sum, image) => sum + image.file.size, file.size)
  return total > MAX_IMAGE_TOTAL_BYTES ? '图片合计不能超过 10 MB' : null
}

function add(files: File[]) {
  error.value = null
  for (const file of files) {
    const problem = rejection(file, images.value)
    if (problem) {
      error.value = problem
      return
    }
    images.value = [...images.value, { id: nextId++, file, url: URL.createObjectURL(file) }]
  }
}

function remove(id: number) {
  const image = images.value.find((i) => i.id === id)
  if (image) {
    URL.revokeObjectURL(image.url)
  }
  images.value = images.value.filter((i) => i.id !== id)
}

function pick(event: Event) {
  const input = event.target as HTMLInputElement
  add([...(input.files ?? [])])
  // 清空 input：移除后再选同一张也能触发 change
  input.value = ''
}

/** Ctrl V 粘贴截图。剪贴板里没有图片时不拦，文字照常粘贴 */
function onPaste(event: ClipboardEvent) {
  const files = [...(event.clipboardData?.files ?? [])].filter((f) => f.type.startsWith('image/'))
  if (files.length > 0) {
    event.preventDefault()
    add(files)
  }
}

/** 等到有结果；超时或离开页面时为 null */
async function waitForResult(taskId: string): Promise<ImageRecognitionView | null> {
  const deadline = Date.now() + POLL_LIMIT_MS
  while (!unmounted && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
    const view = await fetchImageRecognition(taskId)
    if (view.status !== 'RUNNING') {
      return view
    }
  }
  return null
}

async function submit() {
  if (busy.value) {
    return
  }
  if (images.value.length === 0) {
    error.value = '请选择要识别的图片'
    return
  }
  busy.value = true
  error.value = null
  try {
    const { taskId } = await startImageRecognition(images.value.map((i) => i.file))
    const result = await waitForResult(taskId)
    if (unmounted) {
      return
    }
    if (result?.status === 'DONE' && result.text !== null) {
      emit('recognized', result.text)
    } else {
      error.value = result?.message ?? '识别时间太长，可以重试'
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '截图没有识别出来，可以重试'
  } finally {
    busy.value = false
  }
}

onMounted(() => document.addEventListener('paste', onPaste))
onBeforeUnmount(() => {
  unmounted = true
  document.removeEventListener('paste', onPaste)
  images.value.forEach((i) => URL.revokeObjectURL(i.url))
})

defineExpose({ submit })
</script>

<template>
  <div class="field">
    <span class="label">截图</span>
    <input ref="fileInput" type="file" class="file-input" multiple :accept="IMAGE_ACCEPT" @change="pick" />
    <ol v-if="images.length > 0" class="thumbs">
      <li v-for="(image, index) in images" :key="image.id" class="thumb">
        <img :src="image.url" :alt="`第 ${index + 1} 张截图`" />
        <span class="no">{{ index + 1 }}</span>
        <button type="button" class="remove" :aria-label="`移除第 ${index + 1} 张截图`" @click="remove(image.id)">×</button>
      </li>
    </ol>
    <el-button class="pick" :disabled="images.length >= MAX_IMAGES" @click="fileInput?.click()">选择图片</el-button>
    <p class="hint">
      也可以直接按 {{ modKeyLabel }} V 粘贴截图，按上面的顺序识别。最多 {{ MAX_IMAGES }} 张，每张 5 MB，合计 10 MB，支持 PNG、JPG、WebP。图片不保存，只保留识别出的文字。
    </p>
  </div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div class="row">
    <el-button type="primary" class="primary" :loading="busy" @click="submit">
      识别文字<kbd>{{ modKeyLabel }} Enter</kbd>
    </el-button>
    <span v-if="busy" class="hint">正在识别图片里的文字，通常要几十秒</span>
  </div>
</template>

<style scoped>
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.label {
  font-size: 14px;
  font-weight: 700;
}

.file-input {
  display: none;
}

.thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.thumb {
  position: relative;
  width: 96px;
  height: 96px;
  border: 1px solid var(--wg-border-strong);
  border-radius: var(--wg-radius-control);
  overflow: hidden;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.no {
  position: absolute;
  top: 4px;
  left: 4px;
  min-width: 20px;
  padding: 0 5px;
  border-radius: var(--wg-radius-kbd);
  background: var(--wg-text);
  color: var(--wg-bg);
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}

.remove {
  position: absolute;
  top: 0;
  right: 0;
  width: 44px;
  height: 44px;
  padding: 2px 4px 0 0;
  border: none;
  background: none;
  color: var(--wg-warn);
  font-size: 20px;
  line-height: 1;
  text-align: right;
  vertical-align: top;
  cursor: pointer;
}

.pick {
  align-self: flex-start;
  height: 44px;
}

.hint {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.error {
  color: var(--wg-warn);
  font-size: 14px;
}

.row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 16px;
}

.primary {
  height: 48px;
  padding: 0 22px;
  font-size: 16px;
}

.primary kbd {
  margin-left: 12px;
}
</style>
