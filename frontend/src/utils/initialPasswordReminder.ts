const ACKNOWLEDGED_KEY_PREFIX = 'fzsu.initialPasswordReminderAck.'

export function shouldShowInitialPasswordReminder(
  userId: string | null | undefined,
  passwordChangeRequired: boolean,
): boolean {
  if (!userId || !passwordChangeRequired) return false
  try {
    return localStorage.getItem(ACKNOWLEDGED_KEY_PREFIX + userId) !== '1'
  } catch {
    return true
  }
}

export function acknowledgeInitialPasswordReminder(userId: string | null | undefined): void {
  if (!userId) return
  try {
    localStorage.setItem(ACKNOWLEDGED_KEY_PREFIX + userId, '1')
  } catch {
    // 存储不可用时仅关闭本次提醒
  }
}
