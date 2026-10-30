import type { CollegeTask, RoleCode, Submission } from '@/types/api'

export function normalizeRole(role: string): RoleCode | null {
  const map: Record<string, RoleCode> = {
    ADMIN: 'ADMIN', PLATFORM_ADMIN: 'ADMIN', admin: 'ADMIN',
    SCHOOL_LEADER: 'SCHOOL_LEADER', LEADER: 'SCHOOL_LEADER', leader: 'SCHOOL_LEADER',
    SCHOOL_OWNER: 'SCHOOL_OWNER', SCHOOL: 'SCHOOL_OWNER', school: 'SCHOOL_OWNER',
    COLLEGE_OWNER: 'COLLEGE_OWNER', COLLEGE: 'COLLEGE_OWNER', college: 'COLLEGE_OWNER',
  }
  return map[role] || null
}

export function toBackendRoleCode(role: string): string {
  return role === 'ADMIN' ? 'PLATFORM_ADMIN' : role
}

export function hasRole(userRoles: RoleCode[] | undefined, allowed?: RoleCode[]): boolean {
  if (userRoles?.includes('ADMIN')) return true
  if (!allowed?.length) return true
  return Boolean(userRoles?.some((role) => allowed.includes(role)))
}

export const schoolRoles: RoleCode[] = ['ADMIN', 'SCHOOL_OWNER', 'SCHOOL_LEADER']
export const businessRoles: RoleCode[] = ['ADMIN', 'SCHOOL_OWNER', 'SCHOOL_LEADER', 'COLLEGE_OWNER']

export function canTaskAction(task: CollegeTask, action: 'receive' | 'revoke' | 'objection' | 'report', role: RoleCode): boolean {
  if (role === 'ADMIN') {
    if (action === 'receive' || action === 'revoke') return task.task_status === 'PENDING_RECEIVE'
    if (action === 'objection') return ['PENDING_RECEIVE', 'IN_PROGRESS'].includes(task.task_status)
    if (action === 'report') return task.task_status === 'IN_PROGRESS'
    return false
  }
  if (action === 'receive') return role === 'COLLEGE_OWNER' && task.task_status === 'PENDING_RECEIVE'
  if (action === 'revoke') return role === 'SCHOOL_OWNER' && task.task_status === 'PENDING_RECEIVE'
  if (action === 'objection') return role === 'COLLEGE_OWNER' && ['PENDING_RECEIVE', 'IN_PROGRESS'].includes(task.task_status)
  if (action === 'report') return role === 'COLLEGE_OWNER' && task.task_status === 'IN_PROGRESS'
  return false
}

export function canSubmissionAction(submission: Submission, action: 'edit' | 'submit' | 'withdraw' | 'revise' | 'review' | 'correct', role: RoleCode): boolean {
  const status = submission.submission_status
  if (role === 'ADMIN') {
    if (action === 'edit' || action === 'submit') return status === 'DRAFT'
    if (action === 'withdraw') return status === 'UNDER_REVIEW'
    if (action === 'revise') return ['RETURNED', 'WITHDRAWN'].includes(status)
    if (action === 'review') return status === 'UNDER_REVIEW'
    if (action === 'correct') return status === 'APPROVED'
    return false
  }
  if (action === 'edit' || action === 'submit') return role === 'COLLEGE_OWNER' && status === 'DRAFT'
  if (action === 'withdraw') return role === 'COLLEGE_OWNER' && status === 'UNDER_REVIEW'
  if (action === 'revise') return role === 'COLLEGE_OWNER' && ['RETURNED', 'WITHDRAWN'].includes(status)
  if (action === 'review') return role === 'SCHOOL_OWNER' && status === 'UNDER_REVIEW'
  if (action === 'correct') return role === 'SCHOOL_OWNER' && status === 'APPROVED'
  return false
}
