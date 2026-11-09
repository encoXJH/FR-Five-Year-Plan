<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageState from '@/components/PageState.vue'
import { listPlans } from '@/api/plan'
import { getScreenData } from '@/api/screen'
import { useUserStore } from '@/stores/user'
import { useEChart } from '@/utils/chart'
import { formatCount, formatLargeNumber, formatPercent } from '@/utils/format'
import type { PlanCard } from '@/types/plan'
import type { ScreenData } from '@/types/stats'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const planId = ref(String(route.params.planId ?? ''))
const year = ref<number | null>(null)
const plans = ref<PlanCard[]>([])
const data = ref<ScreenData | null>(null)
const loading = ref(false)
const planPickerVisible = ref(false)
const yearPickerVisible = ref(false)

const {
  container: trendChartEl,
  setOption: setTrendOption,
} = useEChart('fzsu-dark')
const {
  container: unitChartEl,
  setOption: setUnitOption,
} = useEChart('fzsu-dark')

const planLabel = computed(() => plans.value.find((item) => item.id === planId.value)?.name ?? '选择计划')
const yearLabel = computed(() => (year.value === null ? '五年累计' : `${year.value} 年`))
const yearOptions = computed(() => {
  const plan = plans.value.find((item) => item.id === planId.value)
  if (!plan) return []
  const list: number[] = []
  for (let y = plan.startYear; y <= plan.endYear; y += 1) list.push(y)
  return list
})

let loadSequence = 0
async function load(): Promise<void> {
  if (!planId.value) return
  const sequence = ++loadSequence
  loading.value = true
  try {
    const result = await getScreenData(planId.value, year.value ?? undefined)
    if (sequence !== loadSequence) return
    data.value = result
    await nextTick()
    renderCharts()
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

function renderCharts(): void {
  if (!data.value) return
  const trend = data.value.trend ?? []
  setTrendOption({
    animation: false,
    grid: { left: 8, right: 24, top: 24, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: trend.map((item) => `${item.year}`), axisLabel: { color: '#8FA7D6', fontSize: 9 } },
    yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%', color: '#8FA7D6', fontSize: 9 }, splitLine: { lineStyle: { color: 'rgba(143,167,214,0.15)' } } },
    series: [{
      type: 'line',
      smooth: true,
      data: trend.map((item) => (item.completionRate == null ? null : Number((item.completionRate * 100).toFixed(1)))),
      lineStyle: { color: '#3DD6F5', width: 2 },
      itemStyle: { color: '#3DD6F5' },
      areaStyle: { color: 'rgba(61,214,245,0.12)' },
      symbolSize: 5,
    }],
  })

  const units = data.value.byUnit ?? []
  setUnitOption({
    animation: false,
    grid: { left: 6, right: 34, top: 6, bottom: 6, containLabel: true },
    xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%', color: '#8FA7D6', fontSize: 9 }, splitLine: { lineStyle: { color: 'rgba(143,167,214,0.15)' } } },
    yAxis: { type: 'category', inverse: true, data: units.map((item) => item.unit?.name ?? '—'), axisLabel: { color: '#8FA7D6', fontSize: 9, width: 74, overflow: 'truncate' } },
    series: [{
      type: 'bar',
      barWidth: 8,
      data: units.map((item) => ({
        value: item.cumulativeRate == null ? 0 : Number((item.cumulativeRate * 100).toFixed(1)),
        itemStyle: { color: '#4F8BFF', borderRadius: [0, 3, 3, 0] },
      })),
      label: { show: true, position: 'right', color: '#8FA7D6', fontSize: 9, formatter: '{c}%' },
    }],
  })
}

async function loadPlans(): Promise<void> {
  try {
    plans.value = await listPlans()
    if (!planId.value) {
      planId.value = plans.value.find((item) => item.status === 'ACTIVE')?.id ?? plans.value[0]?.id ?? ''
    }
    if (planId.value) await load()
  } catch {
    // 拦截器已提示
  }
}

function choosePlan(item: PlanCard): void {
  planId.value = item.id
  year.value = null
  planPickerVisible.value = false
  void router.replace(`/screen/${item.id}`)
  void load()
}

function chooseYear(value: number): void {
  year.value = value === 0 ? null : value
  yearPickerVisible.value = false
  void load()
}

onMounted(loadPlans)

watch(planId, () => {
  if (planId.value && !data.value) void load()
})
</script>

<template>
  <div class="screen-page">
    <div class="screen-head">
      <button type="button" class="back-btn" @click="router.push(userStore.homePath)">
        <van-icon name="arrow-left" size="16" />
      </button>
      <div class="screen-title">
        <h1>五年计划 · 可视化大屏</h1>
        <p>{{ planLabel }} · {{ yearLabel }}</p>
      </div>
      <div class="screen-filters">
        <button type="button" class="screen-filter" @click="planPickerVisible = true">切换计划</button>
        <button type="button" class="screen-filter" @click="yearPickerVisible = true">{{ yearLabel }}</button>
      </div>
    </div>

    <PageState :loading="loading && !data" :empty="!loading && !data" empty-text="暂无大屏数据">
      <template v-if="data">
        <div class="core-grid">
          <div class="core-card">
            <strong>{{ formatPercent(data.overview.cumulativeCompletionRate) }}</strong>
            <span>五年累计完成率</span>
          </div>
          <div class="core-card">
            <strong>{{ formatPercent(data.overview.timeProgress) }}</strong>
            <span>时间进度</span>
          </div>
          <div class="core-card">
            <strong>{{ formatCount(data.coreNumbers?.indicatorACount ?? 0) }}</strong>
            <span>关键指标</span>
          </div>
          <div class="core-card">
            <strong>{{ formatCount(data.coreNumbers?.taskCount ?? data.overview.taskCount) }}</strong>
            <span>学院任务</span>
          </div>
          <div class="core-card">
            <strong>{{ formatLargeNumber(data.coreNumbers?.materialCount ?? 0) }}</strong>
            <span>佐证材料</span>
          </div>
          <div class="core-card">
            <strong>{{ formatCount(data.coreNumbers?.approvedThisYearCount ?? 0) }}</strong>
            <span>本年审核通过</span>
          </div>
        </div>

        <div class="screen-card">
          <div class="screen-card__title">五年完成趋势</div>
          <div ref="trendChartEl" class="screen-chart screen-chart--trend" />
        </div>

        <div class="screen-card">
          <div class="screen-card__title">学院完成率排名</div>
          <div ref="unitChartEl" class="screen-chart screen-chart--rank" />
        </div>

        <div class="screen-card">
          <div class="screen-card__title">关键指标达成</div>
          <div class="indicator-list">
            <div v-for="item in data.byIndicator" :key="item.indicatorId" class="indicator-row">
              <span class="code">{{ item.code }}</span>
              <span class="ellipsis grow">{{ item.name }}</span>
              <span class="small">{{ item.achievedCount }}/{{ item.taskCount }}</span>
              <strong class="small">{{ formatPercent(item.cumulativeRate) }}</strong>
            </div>
          </div>
        </div>

        <div v-if="data.adjustmentStats" class="screen-card">
          <div class="screen-card__title">调整统计</div>
          <div class="adjust-grid">
            <div><strong>{{ data.adjustmentStats.totalCount }}</strong><span>申请总数</span></div>
            <div><strong>{{ data.adjustmentStats.pendingCount }}</strong><span>审批中</span></div>
            <div><strong>{{ data.adjustmentStats.approvedCount }}</strong><span>已生效</span></div>
            <div><strong>{{ data.adjustmentStats.postponeCount }}</strong><span>延期</span></div>
            <div><strong>{{ data.adjustmentStats.transferCount }}</strong><span>转移</span></div>
          </div>
        </div>

        <div v-if="data.recentActivities?.length" class="screen-card">
          <div class="screen-card__title">最近动态</div>
          <div class="activity-list">
            <div v-for="(item, index) in data.recentActivities" :key="index" class="activity-row">
              <span class="small">{{ item.time }}</span>
              <span class="ellipsis grow">{{ item.operatorName }} · {{ item.taskCode }} · {{ item.year }} 年</span>
              <span class="chip">{{ item.result }}</span>
            </div>
          </div>
        </div>
      </template>
    </PageState>

    <van-popup v-model:show="planPickerVisible" position="bottom" round>
      <div class="light-sheet">
        <div class="light-sheet__title">选择计划</div>
        <button
          v-for="item in plans"
          :key="item.id"
          type="button"
          class="light-item"
          @click="choosePlan(item)"
        >{{ item.name }}</button>
      </div>
    </van-popup>

    <van-popup v-model:show="yearPickerVisible" position="bottom" round>
      <div class="light-sheet">
        <div class="light-sheet__title">选择年度</div>
        <button type="button" class="light-item" @click="chooseYear(0)">五年累计</button>
        <button
          v-for="item in yearOptions"
          :key="item"
          type="button"
          class="light-item"
          @click="chooseYear(item)"
        >{{ item }} 年</button>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.screen-page {
  min-height: 100vh;
  padding: calc(12px + var(--safe-top)) 12px calc(20px + var(--safe-bottom));
  background: radial-gradient(circle at 50% 0%, #14306b 0%, #0a1733 60%, #070f20 100%);
  color: #e6eeff;
}
.screen-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.back-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border: 1px solid rgba(143, 167, 214, 0.3);
  border-radius: 10px;
  background: rgba(20, 48, 107, 0.5);
  color: #e6eeff;
  flex-shrink: 0;
}
.screen-title {
  flex: 1;
  min-width: 0;
}
.screen-title h1 {
  margin: 0;
  font-size: 17px;
  letter-spacing: 1px;
}
.screen-title p {
  margin: 2px 0 0;
  color: #8fa7d6;
  font-size: 11px;
}
.screen-filters {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.screen-filter {
  min-height: 28px;
  padding: 0 10px;
  border: 1px solid rgba(143, 167, 214, 0.3);
  border-radius: 14px;
  background: rgba(20, 48, 107, 0.5);
  color: #bcd0f0;
  font-size: 10px;
}
.core-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 10px;
}
.core-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 8px;
  border: 1px solid rgba(79, 139, 255, 0.28);
  border-radius: 10px;
  background: rgba(20, 48, 107, 0.42);
}
.core-card strong {
  color: #3dd6f5;
  font-size: 18px;
  line-height: 24px;
}
.core-card span {
  color: #8fa7d6;
  font-size: 10px;
}
.screen-card {
  margin-bottom: 10px;
  padding: 10px;
  border: 1px solid rgba(79, 139, 255, 0.22);
  border-radius: 12px;
  background: rgba(13, 32, 71, 0.6);
}
.screen-card__title {
  margin-bottom: 6px;
  color: #e6eeff;
  font-size: 13px;
  font-weight: 600;
}
.screen-chart {
  width: 100%;
}
.screen-chart--trend {
  height: 180px;
}
.screen-chart--rank {
  height: 280px;
}
.indicator-list {
  max-height: 300px;
  overflow-y: auto;
}
.indicator-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  border-bottom: 1px solid rgba(143, 167, 214, 0.12);
  font-size: 11px;
}
.code {
  flex-shrink: 0;
  color: #3dd6f5;
  font-family: Consolas, Menlo, monospace;
}
.adjust-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 6px;
}
.adjust-grid > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 4px;
  border-radius: 8px;
  background: rgba(20, 48, 107, 0.4);
  text-align: center;
}
.adjust-grid strong {
  color: #3dd6f5;
  font-size: 15px;
}
.adjust-grid span {
  color: #8fa7d6;
  font-size: 9px;
}
.activity-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.activity-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
  font-size: 11px;
}
.activity-row .chip {
  background: rgba(61, 214, 245, 0.15);
  color: #3dd6f5;
}
.light-sheet {
  padding: 14px 16px calc(14px + var(--safe-bottom));
  color: var(--text);
}
.light-sheet__title {
  margin-bottom: 10px;
  font-size: 16px;
  font-weight: 600;
}
.light-item {
  width: 100%;
  min-height: 46px;
  padding: 8px 4px;
  border: none;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--text);
  text-align: left;
  font-size: 13px;
}
</style>
