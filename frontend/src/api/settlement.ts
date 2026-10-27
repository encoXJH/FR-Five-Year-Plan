import { http } from '@/utils/request'
import type { FinalSettlement, YearSettlement } from '@/types/stats'

/** 年度核算并锁定（幂等，settlement:manage） */
export function settleYear(
  planId: string,
  year: number,
  version: number,
  idempotencyKey: string,
): Promise<YearSettlement> {
  return http.post(
    `/plans/${planId}/years/${year}/settle`,
    { version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

/** 解锁年度数据（敏感操作，必须带 reason；幂等，settlement:manage） */
export function unlockYear(
  planId: string,
  year: number,
  reason: string,
  version: number,
  idempotencyKey: string,
): Promise<void> {
  return http.post(
    `/plans/${planId}/years/${year}/unlock`,
    { reason, version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

/** 五年核算（幂等，settlement:manage） */
export function finalSettle(
  planId: string,
  version: number,
  idempotencyKey: string,
): Promise<FinalSettlement> {
  return http.post(
    `/plans/${planId}/final-settle`,
    { version },
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

/** 年度核算结果（达标 / 未达标 / 待补做清单） */
export function getYearSettlement(planId: string, year: number): Promise<YearSettlement> {
  return http.get(`/plans/${planId}/years/${year}/settlement`)
}
