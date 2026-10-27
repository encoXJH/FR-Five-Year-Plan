import { describe, expect, it } from 'vitest'
import { Sha256, sha256File, sha256Hex } from '../sha256'

/** 用 Node 全局 Web Crypto 做对照 */
async function sha256HexRef(data: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', data as BufferSource)
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

describe('sha256Hex', () => {
  it('标准测试向量', () => {
    expect(sha256Hex('')).toBe('e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855')
    expect(sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
    expect(sha256Hex('abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq')).toBe(
      '248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1',
    )
  })

  it('跨块边界（55 / 56 / 64 / 112 字节，覆盖 padding 分支）', async () => {
    for (const length of [1, 55, 56, 63, 64, 65, 112, 128]) {
      const data = new TextEncoder().encode('a'.repeat(length))
      expect(sha256Hex(data)).toBe(await sha256HexRef(data))
    }
  })

  it('增量 update 与一次性结果一致', async () => {
    const full = new Uint8Array(1000).map((_, i) => i % 256)
    const hasher = new Sha256()
    hasher.update(full.subarray(0, 63)).update(full.subarray(63, 64)).update(full.subarray(64))
    expect(hasher.digestHex()).toBe(await sha256HexRef(full))
  })
})

describe('sha256File', () => {
  it('Blob 分块计算与一次性一致', async () => {
    const bytes = new Uint8Array(9 * 1024 * 1024 + 7).map((_, i) => (i * 31) % 256)
    const blob = new Blob([bytes])
    const progress: number[] = []
    const hex = await sha256File(blob, (ratio) => progress.push(ratio))
    expect(hex).toBe(await sha256HexRef(bytes))
    expect(progress.length).toBeGreaterThan(1)
    expect(progress.at(-1)).toBe(1)
  })
})
