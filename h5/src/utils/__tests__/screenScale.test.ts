import { describe, expect, it } from 'vitest'
import { computeScreenScale } from '../screenScale'

describe('computeScreenScale（大屏 1920×1080 等比缩放，UI 规范 5.3）', () => {
  it('设计稿尺寸原样显示', () => {
    expect(computeScreenScale(1920, 1080)).toBe(1)
  })

  it('宽高同比例缩小时取该比例', () => {
    expect(computeScreenScale(960, 540)).toBe(0.5)
  })

  it('宽不足时以宽为准（高度留白）', () => {
    expect(computeScreenScale(960, 1080)).toBe(0.5)
  })

  it('高不足时以高为准（宽度留白）', () => {
    expect(computeScreenScale(3840, 1080)).toBe(1)
  })

  it('超宽视口不放大超过需求时取较小值', () => {
    expect(computeScreenScale(2560, 1440)).toBeCloseTo(2560 / 1920)
  })

  it('非法视口返回 0', () => {
    expect(computeScreenScale(0, 1080)).toBe(0)
    expect(computeScreenScale(1920, 0)).toBe(0)
    expect(computeScreenScale(-1, -1)).toBe(0)
  })
})
