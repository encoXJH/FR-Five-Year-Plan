/**
 * 飞书客户端内 JSAPI 免登（仅浏览器环境使用，不做单测）。
 * 参考飞书官方文档：
 * - JSAPI 引入（h5-js-sdk）：https://open.feishu.cn/document/uYjL24iN/uYDMuYDMuYDM
 * - 免登获取登录预授权码 tt.requestAccess：https://open.feishu.cn/document/uYjL24iN/uITMuITMuITM
 */

/** 飞书官方 H5 JS SDK CDN 地址（见上述「JSAPI 引入」文档） */
const FEISHU_SDK_URL = 'https://lf1-cdn-tos.bytegoofy.com/goofy/lark/op/h5-js-sdk-1.5.16.js'

interface FeishuH5Sdk {
  ready: (cb: () => void) => void
}

interface FeishuTT {
  requestAccess: (options: {
    appID: string
    scopeList?: string[]
    success: (result: { code: string }) => void
    fail: (error: unknown) => void
  }) => void
}

declare global {
  interface Window {
    h5sdk?: FeishuH5Sdk
    tt?: FeishuTT
  }
}

let sdkPromise: Promise<void> | null = null

/** 注入飞书 JS SDK 脚本（幂等）；加载失败时 reject，由调用方降级到账号密码登录 */
export function loadFeishuSdk(): Promise<void> {
  if (sdkPromise) return sdkPromise
  sdkPromise = new Promise<void>((resolve, reject) => {
    if (window.h5sdk && window.tt) {
      resolve()
      return
    }
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${FEISHU_SDK_URL}"]`)
    const script = existing ?? document.createElement('script')
    if (!existing) {
      script.src = FEISHU_SDK_URL
      script.async = true
      document.head.appendChild(script)
    }
    script.addEventListener('load', () => {
      if (window.h5sdk) {
        resolve()
      } else {
        reject(new Error('飞书 SDK 加载后未找到 window.h5sdk'))
      }
    })
    script.addEventListener('error', () => reject(new Error('飞书 SDK 加载失败')))
  })
  // 加载失败后允许重试
  sdkPromise.catch(() => {
    sdkPromise = null
  })
  return sdkPromise
}

/**
 * 通过飞书 JSAPI 获取免登授权码 code（一次性、短时有效）。
 * 必须在 window.h5sdk.ready 回调中调用 tt.requestAccess（官方文档要求）。
 */
export function requestFeishuAccessCode(appId: string): Promise<string> {
  return new Promise<string>((resolve, reject) => {
    const { h5sdk, tt } = window
    if (!h5sdk || !tt) {
      reject(new Error('飞书 SDK 未加载'))
      return
    }
    h5sdk.ready(() => {
      tt.requestAccess({
        appID: appId,
        scopeList: [],
        success: (result) => {
          if (result && typeof result.code === 'string' && result.code.length > 0) {
            resolve(result.code)
          } else {
            reject(new Error('飞书免登未返回授权码'))
          }
        },
        fail: (error) => reject(error instanceof Error ? error : new Error('飞书免登授权失败')),
      })
    })
  })
}
