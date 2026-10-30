import { describe, expect, it } from 'vitest'
import type { CollegeTask, Submission } from '@/types/api'
import { canSubmissionAction, canTaskAction, hasRole, normalizeRole, toBackendRoleCode } from '@/utils/permissions'
import { roleLabels } from '@/utils/dictionaries'
import router from '@/router'

const task = { id: 1, task_code: 'T', indicator_id: 1, college_org_id: 1, owner_user_id: 1, task_status: 'PENDING_RECEIVE', current_target_version_no: 1, has_adjustment: 0 } as CollegeTask
const submission = { id: 1, milestone_id: 1, version_no: 1, is_current: 1, completion_desc: '', completion_date: '2026-10-29', submission_status: 'UNDER_REVIEW' } as Submission

describe('角色权限与状态可用性', () => {
  it('兼容演示账号角色代码并校验路由元数据', () => {
    expect(normalizeRole('admin')).toBe('ADMIN')
    expect(normalizeRole('PLATFORM_ADMIN')).toBe('ADMIN')
    expect(toBackendRoleCode('ADMIN')).toBe('PLATFORM_ADMIN')
    expect(toBackendRoleCode('SCHOOL_OWNER')).toBe('SCHOOL_OWNER')
    expect(roleLabels.PLATFORM_ADMIN).toBe('平台管理员')
    expect(normalizeRole('school')).toBe('SCHOOL_OWNER')
    expect(hasRole(['COLLEGE_OWNER'], ['SCHOOL_OWNER'])).toBe(false)
    expect(hasRole(['SCHOOL_OWNER'], ['SCHOOL_OWNER'])).toBe(true)
    const adminRoute = router.getRoutes().find((route) => route.name === 'users')
    expect(adminRoute?.meta.roles).toEqual(['ADMIN'])
    expect(hasRole(['COLLEGE_OWNER'], adminRoute?.meta.roles as never)).toBe(false)
  })

  it('任务仅在对应角色与状态下允许接收、撤销和填报', () => {
    expect(canTaskAction(task, 'receive', 'COLLEGE_OWNER')).toBe(true)
    expect(canTaskAction(task, 'receive', 'SCHOOL_OWNER')).toBe(false)
    expect(canTaskAction(task, 'revoke', 'SCHOOL_OWNER')).toBe(true)
    expect(canTaskAction({ ...task, task_status: 'IN_PROGRESS' }, 'revoke', 'SCHOOL_OWNER')).toBe(false)
    expect(canTaskAction({ ...task, task_status: 'IN_PROGRESS' }, 'report', 'COLLEGE_OWNER')).toBe(true)
  })

  it('审核中只能由学院撤回、校级审核，不能继续编辑', () => {
    expect(canSubmissionAction(submission, 'edit', 'COLLEGE_OWNER')).toBe(false)
    expect(canSubmissionAction(submission, 'withdraw', 'COLLEGE_OWNER')).toBe(true)
    expect(canSubmissionAction(submission, 'review', 'SCHOOL_OWNER')).toBe(true)
    expect(canSubmissionAction(submission, 'review', 'SCHOOL_LEADER')).toBe(false)
    expect(canSubmissionAction({ ...submission, submission_status: 'RETURNED' }, 'revise', 'COLLEGE_OWNER')).toBe(true)
  })
})
