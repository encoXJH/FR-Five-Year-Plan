export const INITIAL_PASSWORD = 'fzrj@12345678'

export function passwordChangeError(
  password: string,
  confirmPassword: string,
  rejectInitialPassword = false,
): string {
  if (!password) return '请输入新密码'
  if (password.length < 8 || password.length > 64) return '密码长度必须为 8–64 位'
  if (rejectInitialPassword && password === INITIAL_PASSWORD) return '新密码不能仍为初始密码'
  if (!confirmPassword) return '请再次输入新密码'
  if (password !== confirmPassword) return '两次输入的密码不一致'
  return ''
}
