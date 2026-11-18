export function passwordChangeError(password: string, confirmPassword: string): string {
  if (!password) return '请输入新密码'
  if (password.length < 8 || password.length > 64) return '密码长度必须为 8–64 位'
  if (!confirmPassword) return '请再次输入新密码'
  if (password !== confirmPassword) return '两次输入的密码不一致'
  return ''
}
