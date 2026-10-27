import type { Component } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import {
  Bell,
  Checked,
  Collection,
  DataAnalysis,
  Document,
  Flag,
  Grid,
  HomeFilled,
  List,
  Monitor,
  Setting,
  Switch,
  Tickets,
} from '@element-plus/icons-vue'

export interface MenuItem {
  /** 静态路径或唯一 key（配合 resolvePath 使用时仅作 key） */
  path: string
  title: string
  icon: Component
  /** 任一权限码命中即显示；缺省表示登录即可见 */
  permissions?: string[]
  /** 用户角色全部落在该列表内时隐藏（如纯学院负责人不显示统计报表） */
  hideForRolesOnly?: string[]
  /** 动态解析跳转路径（如计划概览依赖当前路由 planId） */
  resolvePath?: (route: RouteLocationNormalizedLoaded) => string
}

export interface MenuGroup {
  key: string
  title: string
  items: MenuItem[]
}

/** 菜单 × 权限映射（界面设计规范第 6 章信息架构，权限码见 API 约定 4.3） */
export const menuGroups: MenuGroup[] = [
  {
    key: 'workbench',
    title: '工作台',
    items: [
      { path: '/plans', title: '首页', icon: HomeFilled },
      {
        path: 'plan-overview',
        title: '计划概览',
        icon: Flag,
        resolvePath: (route) => {
          const planId = route.params.planId
          // 计划概览路由为 /plans/:planId（见 router/index.ts）
          return typeof planId === 'string' && planId.length > 0 ? `/plans/${planId}` : '/plans'
        },
      },
      { path: '/todos', title: '待办与消息', icon: Bell },
    ],
  },
  {
    key: 'indicator',
    title: '指标管理',
    items: [
      {
        path: 'indicators',
        title: '指标总表',
        icon: List,
        permissions: ['indicator:edit'],
        resolvePath: (route) => {
          const planId = route.params.planId
          // 计划上下文路由为 /plans/:planId/indicators（见 router/index.ts）
          return typeof planId === 'string' && planId.length > 0 ? `/plans/${planId}/indicators` : '/indicators'
        },
      },
      {
        path: 'allocation',
        title: '分解下发',
        icon: Grid,
        permissions: ['allocation:edit'],
        resolvePath: (route) => {
          const planId = route.params.planId
          return typeof planId === 'string' && planId.length > 0 ? `/plans/${planId}/allocation` : '/allocation'
        },
      },
    ],
  },
  {
    key: 'task',
    title: '任务执行',
    items: [
      {
        path: '/workbench',
        title: '学院工作台',
        icon: Tickets,
        permissions: ['task:accept'],
      },
      // 任务填报（P08）为隐藏菜单，从学院工作台任务行进入（UI 规范 6.1）
      { path: '/reviews', title: '审核中心', icon: Checked, permissions: ['submission:review'] },
      {
        path: '/adjustments',
        title: '调整申请与审批',
        icon: Switch,
        permissions: ['adjustment:apply', 'adjustment:decide'],
      },
    ],
  },
  {
    key: 'stats',
    title: '统计分析',
    items: [
      // 纯学院负责人（UNIT_OWNER）不显示统计报表（UI 规范 6.1 / A4）
      { path: '/stats', title: '统计报表', icon: DataAnalysis, hideForRolesOnly: ['UNIT_OWNER'] },
      { path: '/screen', title: '可视化大屏', icon: Monitor, permissions: ['screen:view'] },
    ],
  },
  {
    key: 'system',
    title: '系统',
    items: [
      { path: '/templates', title: '模板管理', icon: Collection, permissions: ['plan:manage'] },
      { path: '/logs', title: '操作日志', icon: Document, permissions: ['audit-log:view'] },
      { path: '/system', title: '系统管理', icon: Setting, permissions: ['system:manage'] },
    ],
  },
]
