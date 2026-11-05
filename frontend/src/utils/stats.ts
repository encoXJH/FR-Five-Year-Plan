import type { UnitStatItem } from '@/types/stats'

/** 五年累计使用累计口径，具体年度使用所选年度的达标口径。 */
export function unitStatRate(
  item: Pick<UnitStatItem, 'completionRate' | 'cumulativeRate'>,
  year: number | null,
): number | null {
  return year === null ? item.cumulativeRate : item.completionRate
}

export function sortUnitStatsByYear(items: UnitStatItem[], year: number | null): UnitStatItem[] {
  return [...items].sort((a, b) => (unitStatRate(b, year) ?? -1) - (unitStatRate(a, year) ?? -1))
}
