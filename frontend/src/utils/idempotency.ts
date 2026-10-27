/** 生成 Idempotency-Key（API 约定 5.6：同一次操作重试复用同一个 Key） */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}
