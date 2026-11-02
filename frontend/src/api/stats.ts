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

/** 旧统计树中的历史中间节点不单独展示，其 B 子任务提升到所属 A。 */
export function normalizeStatsTree(nodes: StatsTreeNode[]): StatsTreeNode[] {
  return nodes.flatMap((node) => {
    if (node.level === 'A_PLUS') return normalizeStatsTree(node.children ?? [])
    return [{ ...node, children: normalizeStatsTree(node.children ?? []) }]
  })
}

/** A → B 逐级下钻，兼容旧统计响应。 */
export function getStatsTree(planId: string, year?: number): Promise<StatsTreeNode[]> {
  return http.get<StatsTreeNode[]>(`/plans/${planId}/stats/tree`, { year }).then(normalizeStatsTree)
}

/** 指标各年度目标与实际趋势 */
export function getIndicatorTrend(indicatorId: string): Promise<IndicatorTrend> {
  return http.get(`/indicators/${indicatorId}/trend`)
}
