import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import IndicatorAssignmentPanel from '@/components/IndicatorAssignmentPanel.vue'
import type { ConstructionPlan, Indicator } from '@/types/api'

const mocks = vi.hoisted(() => ({
  tasks: vi.fn(),
  organizations: vi.fn(),
  users: vi.fn(),
  preview: vi.fn(),
  assign: vi.fn(),
  warning: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))
vi.mock('@/api', () => ({
  errorMessage: (cause: unknown) => String(cause),
  executionApi: { tasks: mocks.tasks, previewDecomposition: mocks.preview, assignTasks: mocks.assign },
  optionsApi: { organizations: mocks.organizations, users: mocks.users },
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('element-plus', () => ({ ElMessage: { warning: mocks.warning, success: mocks.success, error: mocks.error } }))

const stubs = {
  'el-alert': { props: ['title'], template: '<div class="alert-stub">{{ title }}<slot /></div>' },
  'el-select': { props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  'el-option': { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-checkbox': { template: '<label><slot /></label>' },
  'el-input': { template: '<input />' },
  'el-table': { template: '<div><slot /></div>' },
  'el-table-column': { template: '<div />' },
  'el-collapse': { template: '<div><slot /></div>' },
  'el-collapse-item': { template: '<div><slot name="title" /><slot /></div>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { props: ['label'], template: '<label>{{ label }}<slot /></label>' },
  'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
  'el-dialog': { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  'el-icon': { template: '<i><slot /></i>' },
  StatusTag: true,
  TargetValueEditor: true,
  AnnualTargetGrid: true,
}
const plan = { id: 1, plan_status: 'PUBLISHED', start_year: 2026, end_year: 2030 } as ConstructionPlan
function indicator(status = 'PUBLISHED', calcMethod = 'FINAL') {
  return { id: 1, plan_id: 1, direction_id: 1, indicator_code: 'TEST-001', indicator_status: status, calc_method: calcMethod, indicator_name: '测试指标', indicator_desc: '测试指标说明', indicator_type_code: 'QUANTITY', unit_code: 'ITEM', unit_raw: '项', value_kind: 'DECIMAL', professional_five_year_target_value: 1, annual_targets: [], parse_status: 'PARSED', target_version_no: 1, lock_version: 1 } as Indicator
}
function mountPanel(currentIndicator = indicator()) {
  return mount(IndicatorAssignmentPanel, { props: { indicator: currentIndicator, plan, canAssign: true }, global: { stubs, directives: { loading: {} } } })
}
async function addCollegeAndOwner(wrapper: ReturnType<typeof mountPanel>) {
  await wrapper.find('.college-picker select').setValue('college-1')
  const addButton = wrapper.findAll('button').find((item) => item.text().includes('添加接收学院'))
  await addButton?.trigger('click')
  await wrapper.vm.$nextTick()
  const ownerSelect = wrapper.find('.task-edit select')
  await ownerSelect.setValue('owner-1')
}

describe('指标接收与下发面板', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.tasks.mockResolvedValue({ items: [], total: 0 })
    mocks.organizations.mockResolvedValue({ items: [{ id: 'college-1', org_name: '智能产业学院' }], total: 1 })
    mocks.users.mockResolvedValue({ items: [{ id: 'owner-1', user_name: '学院负责人', account_name: 'college', primary_org_id: 'college-1' }], total: 1 })
    mocks.preview.mockResolvedValue({ errors: [], can_assign: true })
    mocks.assign.mockResolvedValue({ items: [{ id: 1 }], total: 1 })
  })

  it('未发布指标明确提示先发布且不显示接收表单', async () => {
    const wrapper = mountPanel(indicator('DRAFT'))
    await flushPromises()
    expect(wrapper.text()).toContain('请先保存并发布指标，再选择接收学院和负责人')
    expect(wrapper.text()).not.toContain('新增接收学院')
  })

  it('外部数据型明确无需学院下发', async () => {
    const wrapper = mountPanel(indicator('PUBLISHED', 'EXTERNAL'))
    await flushPromises()
    expect(wrapper.text()).toContain('外部数据型由校级负责人录入认定，无需下发学院任务')
    expect(mocks.preview).not.toHaveBeenCalled()
  })

  it('添加接收学院后必须选择该学院负责人才能预览', async () => {
    const wrapper = mountPanel()
    await flushPromises()
    await wrapper.find('.college-picker select').setValue('college-1')
    const addButton = wrapper.findAll('button').find((item) => item.text().includes('添加接收学院'))
    await addButton?.trigger('click')
    const previewButton = wrapper.findAll('button').find((item) => item.text().includes('预览差异并下发'))
    await previewButton?.trigger('click')
    expect(mocks.warning).toHaveBeenCalledWith('请为每个接收学院选择学院负责人')
    expect(mocks.preview).not.toHaveBeenCalled()
    expect(mocks.users).toHaveBeenCalledWith({ role: 'COLLEGE_OWNER', status: 'ENABLED', page_size: 200 })
  })

  it('确认下发后返回接收数量并刷新任务列表', async () => {
    const blankTarget = indicator()
    blankTarget.professional_five_year_target_value = null
    const wrapper = mountPanel(blankTarget)
    await flushPromises()
    await addCollegeAndOwner(wrapper)
    const previewButton = wrapper.findAll('button').find((item) => item.text().includes('预览差异并下发'))
    await previewButton?.trigger('click')
    await flushPromises()
    const confirmButton = wrapper.findAll('button').find((item) => item.text().includes('确认下发'))
    await confirmButton?.trigger('click')
    await flushPromises()
    expect(mocks.assign).toHaveBeenCalledOnce()
    expect(wrapper.emitted('assigned')).toEqual([[1]])
    expect(mocks.success).toHaveBeenCalledWith('已下发至 1 个学院，任务待负责人接收')
    expect(mocks.tasks).toHaveBeenCalledTimes(2)
  })
})
