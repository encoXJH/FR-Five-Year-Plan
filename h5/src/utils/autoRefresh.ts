import { getCurrentInstance, onBeforeUnmount, onMounted } from 'vue'

/** 默认轮询间隔：30 秒 */
export const DEFAULT_AUTO_REFRESH_INTERVAL_MS = 30_000

/** 两次自动刷新之间的最小间隔，避免轮询与焦点事件重复触发 */
const MIN_REFRESH_GAP_MS = 3_000

export interface AutoRefreshOptions {
  /** 轮询间隔，默认 30 秒 */
  intervalMs?: number
  /** 返回 false 时暂停自动刷新 */
  enabled?: () => boolean
  /** 返回 true 时跳过本次刷新（如存在未保存编辑） */
  skip?: () => boolean
}

/** 自动刷新判定：页面可见、已启用、无编辑中内容时才执行 */
export function shouldAutoRefresh(input: {
  visible: boolean
  enabled?: boolean
  editing?: boolean
  skipping?: boolean
}): boolean {
  return input.visible && input.enabled !== false && !input.editing && !input.skipping
}

/** 自动刷新响应落地前复核：请求仍有效，且等待期间没有产生需保护的编辑。 */
export function shouldApplyRefreshResponse(input: {
  current: boolean
  silent: boolean
  skipping?: boolean
}): boolean {
  return input.current && !(input.silent && input.skipping)
}

/** 有打开中的弹窗 / 抽屉时视为编辑中，暂停刷新以免覆盖未保存内容 */
export function hasOpenOverlay(root: ParentNode): boolean {
  return Array.from(root.querySelectorAll('.el-overlay')).some((element) => {
    const style = (element as HTMLElement).style
    return style.display !== 'none' && style.visibility !== 'hidden'
  })
}

/**
 * 页面自动刷新：可见时按间隔重新拉取，标签页切回或窗口聚焦时立即刷新；
 * 弹窗、抽屉或页面声明的未保存编辑状态下自动跳过。
 */
export function useAutoRefresh(
  refresh: () => unknown,
  options: AutoRefreshOptions = {},
): void {
  const intervalMs = options.intervalMs ?? DEFAULT_AUTO_REFRESH_INTERVAL_MS
  const instance = getCurrentInstance()
  let timer: ReturnType<typeof setInterval> | null = null
  let running = false
  let lastRunAt = 0

  /** 未激活的 Tab 面板（display:none）不轮询 */
  function isInactivePane(): boolean {
    const element = instance?.proxy?.$el
    if (!(element instanceof HTMLElement)) return false
    const pane = element.closest('.el-tab-pane') as HTMLElement | null
    return pane?.style.display === 'none'
  }

  function canRun(): boolean {
    return shouldAutoRefresh({
      visible: document.visibilityState === 'visible',
      enabled: options.enabled?.() ?? true,
      editing: hasOpenOverlay(document) || isInactivePane(),
      skipping: options.skip?.() ?? false,
    })
  }

  async function run(): Promise<void> {
    if (running || !canRun()) return
    const now = Date.now()
    if (now - lastRunAt < MIN_REFRESH_GAP_MS) return
    lastRunAt = now
    running = true
    try {
      await refresh()
    } catch {
      // 页面加载函数自行处理错误提示；自动刷新失败等待下一轮
    } finally {
      running = false
    }
  }

  function onVisibilityChange(): void {
    if (document.visibilityState === 'visible') void run()
  }

  function onFocus(): void {
    void run()
  }

  onMounted(() => {
    timer = setInterval(() => void run(), intervalMs)
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('focus', onFocus)
  })

  onBeforeUnmount(() => {
    if (timer !== null) clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisibilityChange)
    window.removeEventListener('focus', onFocus)
  })
}
