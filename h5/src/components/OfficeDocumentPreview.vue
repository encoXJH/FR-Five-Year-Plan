<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { officePreviewKind } from '@/utils/file'

/**
 * 浏览器内 Office 文档预览：docx / xls / xlsx / pptx / txt。
 * 旧版 doc / ppt 无法在浏览器解析，提示下载；配置 kkFileView 时不会走到该组件。
 */
const props = defineProps<{
  src: string
  fileName: string
}>()

interface SheetPreview {
  name: string
  html: string
}

interface PptxPreviewerLike {
  preview: (buffer: ArrayBuffer) => Promise<unknown>
  destroy: () => void
}

const SHEET_MAX_ROWS = 200
const SHEET_MAX_COLS = 50
const TEXT_MAX_BYTES = 1024 * 1024

const kind = computed(() => officePreviewKind(props.fileName))
const loading = ref(false)
const errorMessage = ref('')
const sheets = ref<SheetPreview[]>([])
const activeSheet = ref(0)
const sheetTruncated = ref(false)
const textContent = ref('')
const textTruncated = ref(false)

const wordContainer = ref<HTMLElement | null>(null)
const slideContainer = ref<HTMLElement | null>(null)
let slidePreviewer: PptxPreviewerLike | null = null
let loadToken = 0

function destroySlidePreviewer(): void {
  if (slidePreviewer) {
    slidePreviewer.destroy()
    slidePreviewer = null
  }
  if (slideContainer.value) slideContainer.value.innerHTML = ''
}

function resetState(): void {
  destroySlidePreviewer()
  if (wordContainer.value) wordContainer.value.innerHTML = ''
  sheets.value = []
  activeSheet.value = 0
  sheetTruncated.value = false
  textContent.value = ''
  textTruncated.value = false
  errorMessage.value = ''
}

async function fetchArrayBuffer(url: string): Promise<ArrayBuffer> {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return response.arrayBuffer()
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function formatCell(cell: { w?: string; v?: unknown }): string {
  if (typeof cell.w === 'string') return cell.w
  if (cell.v === null || cell.v === undefined) return ''
  if (cell.v instanceof Date) return cell.v.toLocaleString('zh-CN')
  return String(cell.v)
}

function decodeText(buffer: ArrayBuffer): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer)
  } catch {
    return new TextDecoder('gb18030').decode(buffer)
  }
}

async function renderWord(buffer: ArrayBuffer, token: number): Promise<void> {
  const container = wordContainer.value
  if (!container) return
  container.innerHTML = ''
  const { renderAsync } = await import('docx-preview')
  if (token !== loadToken) return
  await renderAsync(buffer, container, undefined, {
    className: 'docx-document',
    inWrapper: true,
    breakPages: true,
    ignoreWidth: false,
    ignoreHeight: false,
    renderHeaders: true,
    renderFooters: true,
    useBase64URL: true,
  })
}

async function renderSheet(buffer: ArrayBuffer): Promise<void> {
  const XLSX = await import('@e965/xlsx')
  const workbook = XLSX.read(buffer, { type: 'array', cellDates: true })
  let truncated = false
  const previews: SheetPreview[] = workbook.SheetNames.map((name) => {
    const result = worksheetHtml(XLSX, workbook.Sheets[name])
    truncated ||= result.truncated
    return { name, html: result.html }
  })
  sheets.value = previews
  activeSheet.value = 0
  sheetTruncated.value = truncated
}

/** 生成带行列号与合并单元格的表格 HTML；单元格文本统一转义，避免文档内容注入 */
function worksheetHtml(
  XLSX: typeof import('@e965/xlsx'),
  worksheet: import('@e965/xlsx').WorkSheet | undefined,
): { html: string; truncated: boolean } {
  if (!worksheet || !worksheet['!ref']) {
    return { html: '<table class="office-sheet"><tbody><tr><td>空工作表</td></tr></tbody></table>', truncated: false }
  }
  const range = XLSX.utils.decode_range(worksheet['!ref'])
  const startRow = range.s.r
  const startCol = range.s.c
  const endRow = Math.min(range.e.r, startRow + SHEET_MAX_ROWS - 1)
  const endCol = Math.min(range.e.c, startCol + SHEET_MAX_COLS - 1)

  const covered = new Set<string>()
  const spans = new Map<string, { rowspan: number; colspan: number }>()
  for (const merge of worksheet['!merges'] ?? []) {
    if (merge.s.r < startRow || merge.s.c < startCol || merge.s.r > endRow || merge.s.c > endCol) continue
    const lastRow = Math.min(merge.e.r, endRow)
    const lastCol = Math.min(merge.e.c, endCol)
    const rowspan = lastRow - merge.s.r + 1
    const colspan = lastCol - merge.s.c + 1
    if (rowspan < 1 || colspan < 1) continue
    spans.set(`${merge.s.r}:${merge.s.c}`, { rowspan, colspan })
    for (let row = merge.s.r; row <= lastRow; row += 1) {
      for (let col = merge.s.c; col <= lastCol; col += 1) {
        if (row !== merge.s.r || col !== merge.s.c) covered.add(`${row}:${col}`)
      }
    }
  }

  const headCells = Array.from({ length: endCol - startCol + 1 }, (_, index) =>
    `<th>${XLSX.utils.encode_col(startCol + index)}</th>`).join('')
  const bodyRows: string[] = []
  for (let row = startRow; row <= endRow; row += 1) {
    const cells = [`<th class="row-no">${row + 1}</th>`]
    for (let col = startCol; col <= endCol; col += 1) {
      const key = `${row}:${col}`
      if (covered.has(key)) continue
      const span = spans.get(key)
      const attrs = span ? ` rowspan="${span.rowspan}" colspan="${span.colspan}"` : ''
      const cell = worksheet[XLSX.utils.encode_cell({ r: row, c: col })]
      const numeric = cell?.t === 'n' || cell?.t === 'd'
      cells.push(`<td${attrs}${numeric ? ' class="numeric"' : ''}>${escapeHtml(formatCell(cell ?? {}))}</td>`)
    }
    bodyRows.push(`<tr>${cells.join('')}</tr>`)
  }

  return {
    html: `<table class="office-sheet"><thead><tr><th class="row-no"></th>${headCells}</tr></thead>`
      + `<tbody>${bodyRows.join('')}</tbody></table>`,
    truncated: range.e.r > endRow || range.e.c > endCol,
  }
}

async function renderSlide(buffer: ArrayBuffer, token: number): Promise<void> {
  const container = slideContainer.value
  if (!container) return
  destroySlidePreviewer()
  const { init } = await import('pptx-preview')
  if (token !== loadToken) return
  const width = Math.max(320, Math.min(1024, container.clientWidth - 24 || 640))
  const previewer = init(container, { width, height: Math.round((width * 9) / 16), mode: 'list' })
  slidePreviewer = previewer
  await previewer.preview(buffer)
}

async function loadText(url: string, token: number): Promise<void> {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  const buffer = await response.arrayBuffer()
  if (token !== loadToken) return
  textTruncated.value = buffer.byteLength > TEXT_MAX_BYTES
  textContent.value = decodeText(buffer.slice(0, TEXT_MAX_BYTES))
}

async function load(): Promise<void> {
  const token = ++loadToken
  resetState()
  if (!props.src || kind.value === 'unsupported') return
  loading.value = true
  try {
    await nextTick()
    if (kind.value === 'text') {
      await loadText(props.src, token)
      return
    }
    const buffer = await fetchArrayBuffer(props.src)
    if (token !== loadToken) return
    if (kind.value === 'word') {
      await renderWord(buffer, token)
    } else if (kind.value === 'sheet') {
      await renderSheet(buffer)
    } else if (kind.value === 'slide') {
      await renderSlide(buffer, token)
    }
  } catch {
    if (token === loadToken) errorMessage.value = '文档解析失败，请下载后查看'
  } finally {
    if (token === loadToken) loading.value = false
  }
}

watch(() => [props.src, props.fileName], load, { immediate: true })

onBeforeUnmount(() => {
  loadToken += 1
  destroySlidePreviewer()
})
</script>

<template>
  <div class="office-preview">
    <div v-if="loading" class="office-loading">正在解析文档…</div>
    <div v-if="errorMessage" class="office-empty">{{ errorMessage }}</div>
    <div v-else-if="kind === 'unsupported'" class="office-empty">该格式暂不支持在线预览，请下载查看</div>
    <template v-else>
      <div v-show="kind === 'word'" ref="wordContainer" class="docx-stage" />
      <div v-if="kind === 'sheet'" class="sheet-stage">
        <div v-if="sheets.length > 1" class="sheet-tabs scroll-x">
          <button
            v-for="(sheet, index) in sheets"
            :key="`${sheet.name}-${index}`"
            type="button"
            class="sheet-tab"
            :class="{ active: index === activeSheet }"
            @click="activeSheet = index"
          >
            {{ sheet.name }}
          </button>
        </div>
        <div v-if="sheets[activeSheet]" class="sheet-scroll" v-html="sheets[activeSheet].html" />
        <p v-if="sheetTruncated" class="preview-note">仅展示前 200 行 / 50 列，完整内容请下载查看。</p>
      </div>
      <div v-show="kind === 'slide'" ref="slideContainer" class="slide-stage" />
      <div v-if="kind === 'text'" class="text-stage">
        <pre class="text-content">{{ textContent }}</pre>
        <p v-if="textTruncated" class="preview-note">文件较大，仅展示前 1 MB 内容，完整内容请下载查看。</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.office-preview {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: #f6f8fb;
}
.office-loading,
.office-empty {
  padding: 40px 16px;
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
}
.docx-stage {
  height: 100%;
  overflow: auto;
  padding: 10px;
  box-sizing: border-box;
}
.docx-stage :deep(.docx-wrapper) {
  background: transparent;
  padding: 0;
}
.docx-stage :deep(.docx-wrapper > section.docx) {
  margin: 0 auto 10px;
  box-shadow: 0 1px 4px rgb(0 0 0 / 12%);
}
.sheet-stage {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: #fff;
}
.sheet-tabs {
  display: flex;
  gap: 2px;
  padding: 8px 10px 0;
  overflow-x: auto;
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}
.sheet-tab {
  padding: 6px 14px;
  border: 1px solid transparent;
  border-bottom: none;
  border-radius: 4px 4px 0 0;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  white-space: nowrap;
}
.sheet-tab.active {
  border-color: var(--line);
  background: #fff;
  color: var(--brand);
  font-weight: 600;
}
.sheet-scroll {
  flex: 1;
  min-height: 0;
  padding: 10px;
  overflow: auto;
}
.sheet-scroll :deep(.office-sheet) {
  border-collapse: collapse;
  font-size: 12px;
  color: var(--text);
  background: #fff;
}
.sheet-scroll :deep(.office-sheet th),
.sheet-scroll :deep(.office-sheet td) {
  border: 1px solid var(--line);
  padding: 4px 8px;
  white-space: nowrap;
  text-align: left;
  vertical-align: middle;
}
.sheet-scroll :deep(.office-sheet thead th) {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #f6f8fb;
  color: var(--text-secondary);
  font-weight: 500;
  text-align: center;
}
.sheet-scroll :deep(.office-sheet .row-no) {
  position: sticky;
  left: 0;
  z-index: 1;
  background: #f6f8fb;
  color: var(--text-secondary);
  font-weight: 500;
  text-align: center;
}
.sheet-scroll :deep(.office-sheet thead .row-no) {
  z-index: 2;
}
.sheet-scroll :deep(.office-sheet td.numeric) {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.slide-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 10px;
  gap: 10px;
  box-sizing: border-box;
}
.text-stage {
  height: 100%;
  overflow: auto;
  background: #fff;
}
.text-content {
  margin: 0;
  padding: 14px;
  color: var(--text);
  font-family: Consolas, Menlo, monospace;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.preview-note {
  margin: 0;
  padding: 8px 10px;
  color: var(--text-secondary);
  font-size: 12px;
  text-align: center;
}
</style>
