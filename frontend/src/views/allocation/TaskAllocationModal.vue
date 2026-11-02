<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'
import AllocationPage from './index.vue'

const props = defineProps<{
  modelValue: boolean
  planId: string
  indicatorId: string | null
  indicatorCode?: string
  indicatorName?: string
}>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  changed: [indicatorId: string]
}>()

interface Rect {
  x: number
  y: number
  width: number
  height: number
}

type ResizeDirection = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw'

const dialogRef = ref<HTMLElement>()
const rect = reactive<Rect>({ x: 0, y: 0, width: 0, height: 0 })
let previousBodyOverflow = ''
let interaction: {
  type: 'drag' | 'resize'
  direction?: ResizeDirection
  pointerId: number
  startX: number
  startY: number
  origin: Rect
} | null = null

function viewportBounds(): { width: number; height: number; minWidth: number; minHeight: number } {
  const width = window.innerWidth
  const height = window.innerHeight
  return {
    width,
    height,
    minWidth: Math.min(760, width - 16),
    minHeight: Math.min(540, height - 16),
  }
}

function keepInViewport(): void {
  const bounds = viewportBounds()
  rect.width = Math.max(bounds.minWidth, Math.min(rect.width, bounds.width - 16))
  rect.height = Math.max(bounds.minHeight, Math.min(rect.height, bounds.height - 16))
  rect.x = Math.max(8, Math.min(rect.x, bounds.width - rect.width - 8))
  rect.y = Math.max(8, Math.min(rect.y, bounds.height - rect.height - 8))
}

function resetRect(): void {
  const bounds = viewportBounds()
  rect.width = Math.min(1680, bounds.width - 40)
  rect.height = Math.min(940, bounds.height - 40)
  rect.x = (bounds.width - rect.width) / 2
  rect.y = (bounds.height - rect.height) / 2
  keepInViewport()
}

function close(): void {
  emit('update:modelValue', false)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && props.modelValue && !document.querySelector('.el-message-box__wrapper, .el-overlay')) {
    close()
  }
}

function startDrag(event: PointerEvent): void {
  if (event.button !== 0 || (event.target as HTMLElement).closest('button')) return
  interaction = {
    type: 'drag',
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    origin: { ...rect },
  }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function startResize(direction: ResizeDirection, event: PointerEvent): void {
  if (event.button !== 0) return
  event.preventDefault()
  interaction = {
    type: 'resize',
    direction,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    origin: { ...rect },
  }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onPointerMove(event: PointerEvent): void {
  if (!interaction || interaction.pointerId !== event.pointerId) return
  const dx = event.clientX - interaction.startX
  const dy = event.clientY - interaction.startY
  const origin = interaction.origin
  const bounds = viewportBounds()
  if (interaction.type === 'drag') {
    rect.x = Math.max(8, Math.min(origin.x + dx, bounds.width - origin.width - 8))
    rect.y = Math.max(8, Math.min(origin.y + dy, bounds.height - origin.height - 8))
    return
  }

  const direction = interaction.direction!
  if (direction.includes('e')) rect.width = Math.max(bounds.minWidth, Math.min(origin.width + dx, bounds.width - origin.x - 8))
  if (direction.includes('s')) rect.height = Math.max(bounds.minHeight, Math.min(origin.height + dy, bounds.height - origin.y - 8))
  if (direction.includes('w')) {
    const width = Math.max(bounds.minWidth, Math.min(origin.width - dx, origin.x + origin.width - 8))
    rect.x = origin.x + origin.width - width
    rect.width = width
  }
  if (direction.includes('n')) {
    const height = Math.max(bounds.minHeight, Math.min(origin.height - dy, origin.y + origin.height - 8))
    rect.y = origin.y + origin.height - height
    rect.height = height
  }
}

function stopInteraction(event: PointerEvent): void {
  if (interaction?.pointerId === event.pointerId) interaction = null
}

watch(() => props.modelValue, async (visible) => {
  if (visible) {
    previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    resetRect()
    await nextTick()
    dialogRef.value?.focus()
  } else {
    document.body.style.overflow = previousBodyOverflow
    interaction = null
  }
}, { immediate: true })

onMounted(() => {
  window.addEventListener('resize', keepInViewport)
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', stopInteraction)
  window.addEventListener('pointercancel', stopInteraction)
})

onBeforeUnmount(() => {
  document.body.style.overflow = previousBodyOverflow
  window.removeEventListener('resize', keepInViewport)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', stopInteraction)
  window.removeEventListener('pointercancel', stopInteraction)
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="allocation-modal-mask"
      data-testid="allocation-modal-mask"
      @click.self="close"
    >
      <section
        ref="dialogRef"
        class="allocation-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="allocation-modal-title"
        tabindex="-1"
        :style="{ left: `${rect.x}px`, top: `${rect.y}px`, width: `${rect.width}px`, height: `${rect.height}px` }"
      >
        <header class="allocation-modal__header" @pointerdown="startDrag">
          <div class="allocation-modal__heading">
            <h2 id="allocation-modal-title">任务分解</h2>
            <p v-if="indicatorCode">{{ indicatorCode }}<span v-if="indicatorName"> · {{ indicatorName }}</span></p>
          </div>
          <button type="button" class="allocation-modal__close" aria-label="关闭任务分解" @click="close">
            <el-icon :size="20"><Close /></el-icon>
          </button>
        </header>

        <main class="allocation-modal__body">
          <AllocationPage
            :key="`${planId}-${indicatorId}`"
            embedded
            :plan-id="planId"
            :initial-indicator-id="indicatorId ?? ''"
            @changed="emit('changed', $event)"
          />
        </main>

        <span
          v-for="direction in ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as ResizeDirection[]"
          :key="direction"
          class="allocation-modal__resize"
          :class="`is-${direction}`"
          :aria-hidden="true"
          @pointerdown="startResize(direction, $event)"
        />
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.allocation-modal-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgb(0 0 0 / 48%);
  overscroll-behavior: contain;
}

.allocation-modal {
  position: fixed;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  box-shadow: 0 24px 80px rgb(0 0 0 / 28%);
  outline: none;
}

.allocation-modal__header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  min-height: 68px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  cursor: move;
  touch-action: none;
  user-select: none;
}

.allocation-modal__heading {
  min-width: 0;
}

.allocation-modal__heading h2 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 600;
  line-height: 26px;
}

.allocation-modal__heading p {
  margin: 2px 0 0;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.allocation-modal__close {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin-left: 16px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-secondary);
  cursor: pointer;
}

.allocation-modal__close:hover {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}

.allocation-modal__body {
  flex: 1;
  min-height: 0;
  padding: 16px 20px 20px;
  overflow: hidden;
  background: var(--el-bg-color-page);
}

.allocation-modal__resize {
  position: absolute;
  z-index: 2;
  touch-action: none;
}

.allocation-modal__resize.is-n,
.allocation-modal__resize.is-s {
  right: 12px;
  left: 12px;
  height: 7px;
  cursor: ns-resize;
}

.allocation-modal__resize.is-n { top: -2px; }
.allocation-modal__resize.is-s { bottom: -2px; }

.allocation-modal__resize.is-e,
.allocation-modal__resize.is-w {
  top: 12px;
  bottom: 12px;
  width: 7px;
  cursor: ew-resize;
}

.allocation-modal__resize.is-e { right: -2px; }
.allocation-modal__resize.is-w { left: -2px; }

.allocation-modal__resize.is-ne,
.allocation-modal__resize.is-nw,
.allocation-modal__resize.is-se,
.allocation-modal__resize.is-sw {
  width: 14px;
  height: 14px;
}

.allocation-modal__resize.is-ne { top: -2px; right: -2px; cursor: nesw-resize; }
.allocation-modal__resize.is-sw { bottom: -2px; left: -2px; cursor: nesw-resize; }
.allocation-modal__resize.is-nw { top: -2px; left: -2px; cursor: nwse-resize; }
.allocation-modal__resize.is-se { right: -2px; bottom: -2px; cursor: nwse-resize; }

@media (max-width: 768px) {
  .allocation-modal {
    border-radius: 8px;
  }

  .allocation-modal__header {
    min-height: 60px;
    padding: 10px 14px;
    cursor: default;
  }

  .allocation-modal__body {
    padding: 12px;
  }

  .allocation-modal__close {
    width: 40px;
    height: 40px;
  }

  .allocation-modal__resize {
    display: none;
  }
}

@media (prefers-reduced-motion: no-preference) {
  .allocation-modal {
    animation: allocation-modal-in 0.16s ease-out;
  }

  @keyframes allocation-modal-in {
    from { opacity: 0; }
    to { opacity: 1; }
  }
}
</style>
