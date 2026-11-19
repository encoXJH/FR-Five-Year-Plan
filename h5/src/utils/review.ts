import type { ReviewNode, Role, SubmissionStatus } from '@/types/enums'
import type { MetricValue } from '@/types/submission'

export interface ReviewNodeTarget {
  status: SubmissionStatus
  currentReviewNode?: ReviewNode | null
  value: MetricValue
  approvedValue?: MetricValue | null
}

/** 前端仅做操作入口守卫，节点权限和任务归属仍由服务端最终校验。 */
export function canReviewNode(roles: readonly Role[], node: ReviewNode | null | undefined): boolean {
  if (roles.includes('ADMIN')) return node === 'UNIT_REVIEW' || node === 'LEADER_REVIEW'
  if (node === 'UNIT_REVIEW') return roles.includes('UNIT_OWNER')
  if (node === 'LEADER_REVIEW') return roles.includes('LEADER')
  return false
}

export function canReviewSubmission(roles: readonly Role[], target: ReviewNodeTarget): boolean {
  return target.status === 'PENDING_REVIEW' && canReviewNode(roles, target.currentReviewNode)
}

/** 校审继承院审暂存认定值；院审或无暂存值时从填报值开始。 */
export function initialReviewValue(target: ReviewNodeTarget): MetricValue {
  const source = target.currentReviewNode === 'LEADER_REVIEW' && target.approvedValue
    ? target.approvedValue
    : target.value
  return { ...source }
}
