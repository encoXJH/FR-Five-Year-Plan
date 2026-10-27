import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { ECharts, EChartsOption } from 'echarts'
import type { Ref } from 'vue'
import { throttleTrailing } from '@/utils/screenScale'

/** 浅色图表色板（界面设计规范 2.4，按顺序取用） */
export const CHART_COLORS = [
  '#2B5BC0',
  '#13A8A8',
  '#E6A23C',
  '#8E5CD9',
  '#67C23A',
  '#F56C6C',
  '#5B8FF9',
  '#F6903D',
  '#9AA7C2',
] as const

/** 「目标」系列固定色（主色 light-7） */
export const CHART_TARGET_COLOR = '#BFCEEC'
/** 「实际」系列固定色（主色） */
export const CHART_ACTUAL_COLOR = '#2B5BC0'
/** 「时间进度」参考线固定色（橙色虚线） */
export const CHART_TIME_PROGRESS_COLOR = '#E6A23C'
/** 「超额完成」系列固定色（2.4 扩展色） */
export const CHART_EXCEED_COLOR = '#2BA471'

let darkThemeRegistered = false

/** 注册大屏深色主题 fzsu-dark（界面设计规范 2.6 / 第 9 章），幂等 */
export function registerScreenTheme(): void {
  if (darkThemeRegistered) return
  echarts.registerTheme('fzsu-dark', {
    color: ['#3DD6F5', '#4F8BFF', '#3DDC97', '#FFB547', '#8e5cd9', '#FF5B5B', '#13a8a8'],
    backgroundColor: 'transparent',
    textStyle: { color: '#8FA7D6' },
    title: { textStyle: { color: '#E6EEFF' } },
    legend: { textStyle: { color: '#8FA7D6' } },
    categoryAxis: {
      axisLine: { lineStyle: { color: 'rgba(143, 167, 214, 0.45)' } },
      axisLabel: { color: '#8FA7D6' },
      splitLine: { lineStyle: { color: 'rgba(143, 167, 214, 0.15)' } },
    },
    valueAxis: {
      axisLine: { lineStyle: { color: 'rgba(143, 167, 214, 0.45)' } },
      axisLabel: { color: '#8FA7D6' },
      splitLine: { lineStyle: { color: 'rgba(143, 167, 214, 0.15)' } },
    },
    tooltip: {
      backgroundColor: 'rgba(10, 28, 70, 0.92)',
      borderColor: 'rgba(79, 139, 255, 0.35)',
      textStyle: { color: '#E6EEFF' },
    },
  })
  darkThemeRegistered = true
}

/**
 * ECharts 容器组合式函数：容器就绪时初始化（支持 v-if 后挂载的容器），
 * ResizeObserver 自适应，卸载时销毁。数据就绪并 await nextTick() 后调用 setOption 渲染。
 */
export function useEChart(theme?: string) {
  const container = ref<HTMLDivElement | null>(null)
  const chart = shallowRef<ECharts | null>(null)
  let observer: ResizeObserver | null = null
  const resize = throttleTrailing(() => chart.value?.resize(), 100)

  function ensureChart(): ECharts | null {
    const el = container.value
    if (!el) return null
    // 容器被 v-if 重建（chart 仍绑定旧元素）时先销毁再重建
    if (chart.value && chart.value.getDom() !== el) {
      chart.value.dispose()
      chart.value = null
    }
    if (!chart.value) {
      if (theme === 'fzsu-dark') registerScreenTheme()
      chart.value = echarts.init(el, theme)
      observer?.disconnect()
      observer = new ResizeObserver(resize)
      observer.observe(el)
    }
    return chart.value
  }

  onMounted(ensureChart)

  onBeforeUnmount(() => {
    resize.cancel()
    observer?.disconnect()
    observer = null
    chart.value?.dispose()
    chart.value = null
  })

  function setOption(option: EChartsOption): void {
    ensureChart()?.setOption(option)
  }

  return { container, chart, setOption }
}

/** 大屏数字滚动时长（界面设计规范 9.2：800ms 滚动动画） */
export const SCREEN_COUNT_UP_DURATION_MS = 800

/**
 * 数字滚动（CountUp，规范 9.2）：requestAnimationFrame 线性插值，目标值变化时从当前值滚动过渡；
 * formatter 应用于每一帧（如 formatLargeNumber）；目标为 null / 非有限数时显示 '—'。
 */
export function useCountUp(
  target: Ref<number | null | undefined>,
  formatter: (value: number) => string,
  durationMs: number = SCREEN_COUNT_UP_DURATION_MS,
): Ref<string> {
  const display = ref('—')
  let rafId: number | null = null
  let animatedValue = 0

  function stop(): void {
    if (rafId != null) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
  }

  watch(
    target,
    (next) => {
      stop()
      if (next == null || !Number.isFinite(next)) {
        display.value = '—'
        return
      }
      const from = animatedValue
      const to = next
      if (from === to || durationMs <= 0) {
        animatedValue = to
        display.value = formatter(to)
        return
      }
      const startAt = performance.now()
      const step = (nowTs: number): void => {
        const progress = Math.min(1, (nowTs - startAt) / durationMs)
        animatedValue = from + (to - from) * progress
        display.value = progress < 1 ? formatter(animatedValue) : formatter(to)
        if (progress < 1) {
          rafId = requestAnimationFrame(step)
        } else {
          rafId = null
          animatedValue = to
        }
      }
      rafId = requestAnimationFrame(step)
    },
    { immediate: true },
  )

  onBeforeUnmount(stop)

  return display
}

export type { EChartsOption }
