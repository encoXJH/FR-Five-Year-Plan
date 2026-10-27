import { http } from '@/utils/request'
import type {
  IndicatorStat,
  IndicatorTrend,
  StatsOverview,
  StatsTreeNode,
  UnitStatItem,
} from '@/types/stats'

/** 总体概览（API 约定 15.8）；year 缺省为五年累计 */
export function getStatsOverview(planId: string, year?: number): Promise<StatsOverview> {
  return http.get(`/plans/${planId}/stats/overview`, { year })
}

/** 按执行单元统计 */
export function getStatsByUnit(planId: string, year?: number): Promise<UnitStatItem[]> {
  return http.get(`/plans/${planId}/stats/by-unit`, { year })
}

/** 按 A 类指标统计 */
export function getStatsByIndicator(planId: string, year?: number): Promise<IndicatorStat[]> {
  return http.get(`/plans/${planId}/stats/by-indicator`, { year })
}

/** A → A+ → B 逐级下钻 */
export function getStatsTree(planId: string, year?: number): Promise<StatsTreeNode[]> {
  return http.get(`/plans/${planId}/stats/tree`, { year })
}

/** 指标各年度目标与实际趋势 */
export function getIndicatorTrend(indicatorId: string): Promise<IndicatorTrend> {
  return http.get(`/indicators/${indicatorId}/trend`)
}
