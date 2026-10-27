import { describe, expect, it } from 'vitest'
import {
  detectClientEnv,
  extractAppIdFromUrl,
  isFeishuUnavailableCode,
  isFeishuUA,
  normalizeAuthorizeInfo,
  parseFeishuCallbackSearch,
} from '../feishu'

describe('detectClientEnv', () => {
  it('普通浏览器 UA 判定为 WEB', () => {
    expect(
      detectClientEnv(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
      ),
    ).toBe('WEB')
  })

  it('飞书移动端 UA 判定为 FEISHU_MOBILE', () => {
    expect(
      detectClientEnv(
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Lark/7.30.0 Mobile',
      ),
    ).toBe('FEISHU_MOBILE')
  })

  it('飞书 PC 端 UA 判定为 FEISHU_PC', () => {
    expect(
      detectClientEnv(
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Lark/7.30.0 Chrome/122.0.0.0',
      ),
    ).toBe('FEISHU_PC')
  })

  it('Feishu 关键字同样识别，且不区分大小写', () => {
    expect(detectClientEnv('Mozilla/5.0 FEISHU/1.0 Android')).toBe('FEISHU_MOBILE')
    expect(isFeishuUA('Mozilla/5.0 FEISHU/1.0 Android')).toBe(true)
    expect(isFeishuUA('Mozilla/5.0 Chrome/130.0.0.0')).toBe(false)
  })
})

describe('isFeishuUnavailableCode', () => {
  it('20006 / 90001 视为飞书未配置', () => {
    expect(isFeishuUnavailableCode(20006)).toBe(true)
    expect(isFeishuUnavailableCode(90001)).toBe(true)
  })

  it('其他错误码不视为未配置', () => {
    expect(isFeishuUnavailableCode(20007)).toBe(false)
    expect(isFeishuUnavailableCode(10001)).toBe(false)
    expect(isFeishuUnavailableCode(undefined)).toBe(false)
    expect(isFeishuUnavailableCode(null)).toBe(false)
  })
})

describe('parseFeishuCallbackSearch', () => {
  it('解析 code 参数', () => {
    const result = parseFeishuCallbackSearch('?code=f2a9c7e1b3d4')
    expect(result.code).toBe('f2a9c7e1b3d4')
    expect(result.error).toBeNull()
  })

  it('兼容不带问号前缀的查询串', () => {
    expect(parseFeishuCallbackSearch('code=abc123').code).toBe('abc123')
  })

  it('授权失败时返回 error 与 error_description', () => {
    const result = parseFeishuCallbackSearch('?error=access_denied&error_description=%E7%94%A8%E6%88%B7%E5%8F%96%E6%B6%88')
    expect(result.code).toBeNull()
    expect(result.error).toBe('access_denied')
    expect(result.errorDescription).toBe('用户取消')
  })

  it('空查询串返回全 null', () => {
    const result = parseFeishuCallbackSearch('')
    expect(result).toEqual({ code: null, error: null, errorDescription: null })
  })
})

describe('extractAppIdFromUrl', () => {
  it('从 OAuth 授权地址解析 app_id', () => {
    expect(
      extractAppIdFromUrl('https://open.feishu.cn/open-apis/authen/v1/authorize?app_id=cli_a1b2c3&redirect_uri=https%3A%2F%2Fx.com%2Fcb'),
    ).toBe('cli_a1b2c3')
  })

  it('无 app_id 或非法地址返回 null', () => {
    expect(extractAppIdFromUrl('https://open.feishu.cn/authorize?redirect_uri=x')).toBeNull()
    expect(extractAppIdFromUrl('')).toBeNull()
  })
})

describe('normalizeAuthorizeInfo', () => {
  it('标准 url 字段', () => {
    expect(normalizeAuthorizeInfo({ url: 'https://open.feishu.cn/x?app_id=cli_1' })).toEqual({
      url: 'https://open.feishu.cn/x?app_id=cli_1',
      appId: 'cli_1',
    })
  })

  it('兼容 authorizeUrl / app_id 命名', () => {
    expect(normalizeAuthorizeInfo({ authorizeUrl: 'https://x', app_id: 'cli_2' })).toEqual({
      url: 'https://x',
      appId: 'cli_2',
    })
  })

  it('显式 appId 优先于 url 解析', () => {
    expect(
      normalizeAuthorizeInfo({ url: 'https://x?app_id=cli_1', appId: 'cli_9' })?.appId,
    ).toBe('cli_9')
  })

  it('缺少 url 或非对象返回 null（视为飞书不可用）', () => {
    expect(normalizeAuthorizeInfo({})).toBeNull()
    expect(normalizeAuthorizeInfo(null)).toBeNull()
    expect(normalizeAuthorizeInfo('x')).toBeNull()
    expect(normalizeAuthorizeInfo({ url: '' })).toBeNull()
  })
})
