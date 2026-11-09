/** 大屏 1920×1080 设计稿等比缩放（界面设计规范 5.3 / 9.1） */

export const SCREEN_DESIGN_WIDTH = 1920
export const SCREEN_DESIGN_HEIGHT = 1080

/**
 * 计算等比缩放比：取宽 / 高缩放比的较小值，居中留白；
 * 视口尺寸非法（≤ 0）时返回 0（组件不渲染内容）。
 */
export function computeScreenScale(
  viewportWidth: number,
  viewportHeight: number,
  designWidth: number = SCREEN_DESIGN_WIDTH,
  designHeight: number = SCREEN_DESIGN_HEIGHT,
): number {
  if (viewportWidth <= 0 || viewportHeight <= 0 || designWidth <= 0 || designHeight <= 0) {
    return 0
  }
  return Math.min(viewportWidth / designWidth, viewportHeight / designHeight)
}

/**
 * trailing 节流：等待窗口内的多次调用合并为一次，在等待期满后以最后一组参数执行；
 * 返回函数带 cancel()，用于组件卸载时清理未执行的调用。
 */
export function throttleTrailing<A extends unknown[]>(
  fn: (...args: A) => void,
  waitMs: number,
): ((...args: A) => void) & { cancel: () => void } {
  let timer: ReturnType<typeof setTimeout> | null = null
  let pendingArgs: A | null = null
  const wrapped = (...args: A): void => {
    pendingArgs = args
    if (timer != null) return
    timer = setTimeout(() => {
      timer = null
      const runArgs = pendingArgs
      pendingArgs = null
      if (runArgs != null) fn(...runArgs)
    }, waitMs)
  }
  wrapped.cancel = (): void => {
    if (timer != null) {
      clearTimeout(timer)
      timer = null
    }
    pendingArgs = null
  }
  return wrapped
}
