import { describe, expect, it } from 'vitest'
import { INITIAL_PASSWORD, passwordChangeError } from '../password'

describe('修改密码校验', () => {
  it('密码不能为空', () => {
    expect(passwordChangeError('', '')).toBe('请输入新密码')
  })

  it('密码长度必须为 8–64 位', () => {
    expect(passwordChangeError('1234567', '1234567')).toBe('密码长度必须为 8–64 位')
    expect(passwordChangeError('a'.repeat(65), 'a'.repeat(65))).toBe('密码长度必须为 8–64 位')
  })

  it('必须再次输入新密码', () => {
    expect(passwordChangeError('NewPass@123', '')).toBe('请再次输入新密码')
  })

  it('两次密码必须一致', () => {
    expect(passwordChangeError('NewPass@123', 'Different@123')).toBe('两次输入的密码不一致')
  })

  it('首次改密不能继续使用初始密码', () => {
    expect(passwordChangeError(INITIAL_PASSWORD, INITIAL_PASSWORD, true)).toBe('新密码不能仍为初始密码')
  })

  it('两次密码一致时通过', () => {
    expect(passwordChangeError('NewPass@123', 'NewPass@123')).toBe('')
  })
})
