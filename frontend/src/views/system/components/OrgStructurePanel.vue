<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getOrgDepartmentTree } from '@/api/system'
import { useUserStore } from '@/stores/user'
import { enumLabel } from '@/utils/enumLabels'
import { useAutoRefresh } from '@/utils/autoRefresh'
import type { OrgDepartmentNode } from '@/types/system'

const userStore = useUserStore()

const loading = ref(false)
const tree = ref<OrgDepartmentNode[]>([])
const keyword = ref('')
const treeRef = ref()

const rootId = computed(() => tree.value[0]?.id ?? '')
const totalCount = computed(() => countNodes(tree.value) - (tree.value.length ? 1 : 0))
const categoryCount = computed(() => tree.value[0]?.children?.length ?? 0)

function countNodes(nodes: OrgDepartmentNode[]): number {
  return nodes.reduce((sum, node) => sum + 1 + countNodes(node.children ?? []), 0)
}

function isRoot(node: OrgDepartmentNode): boolean {
  return node.id === rootId.value
}

function isCategory(node: OrgDepartmentNode): boolean {
  return node.parentId === rootId.value
}

function filterNode(value: string, data: OrgDepartmentNode): boolean {
  return !value || data.name.includes(value)
}

watch(keyword, (value) => treeRef.value?.filter(value))

async function load(silent = false): Promise<void> {
  if (!silent) loading.value = true
  try {
    tree.value = await getOrgDepartmentTree()
  } finally {
    if (!silent) loading.value = false
  }
}

onMounted(load)
useAutoRefresh(() => load(true))
</script>

<template>
  <div v-loading="loading">
    <div class="org-toolbar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="搜索组织名称"
        style="width: 260px"
      />
      <el-button @click="load()">刷新</el-button>
      <div class="org-summary">
        <span>{{ categoryCount }} 个组织分类</span>
        <span class="org-summary__dot">·</span>
        <span>{{ totalCount }} 个下属组织</span>
      </div>
    </div>

    <el-card shadow="never" class="org-card">
      <el-tree
        ref="treeRef"
        :data="tree"
        node-key="id"
        :props="{ label: 'name', children: 'children' }"
        :filter-node-method="filterNode"
        default-expand-all
        :expand-on-click-node="false"
        class="org-tree"
      >
        <template #default="{ data }">
          <span class="org-node">
            <span class="org-node__name" :class="{ 'is-root': isRoot(data) }">{{ data.name }}</span>
            <el-tag
              v-if="isCategory(data)"
              size="small"
              effect="plain"
              class="org-node__tag"
            >
              {{ enumLabel('OrgDepartmentCategory', data.category, userStore.enums) }}
            </el-tag>
            <span v-if="data.children?.length" class="org-node__count">
              {{ data.children.length }} 个
            </span>
            <el-tag v-if="!data.isEnabled" size="small" type="info">停用</el-tag>
          </span>
        </template>
      </el-tree>
      <el-empty v-if="!loading && !tree.length" :image-size="70" description="暂无组织架构数据" />
    </el-card>
  </div>
</template>

<style scoped>
.org-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-4);
}

.org-summary {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.org-summary__dot {
  margin: 0 6px;
}

.org-card {
  border: 1px solid var(--el-border-color-lighter);
}

.org-tree {
  --el-tree-node-hover-bg-color: #f5f8ff;
}

.org-node {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
}

.org-node__name {
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.org-node__name.is-root {
  font-size: 15px;
  font-weight: 600;
  color: #2b5bc0;
}

.org-node__tag {
  color: #2b5bc0;
  border-color: #c9d8f5;
  background: #f2f6ff;
}

.org-node__count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

@media (max-width: 768px) {
  .org-toolbar {
    flex-wrap: wrap;
  }

  .org-toolbar :deep(.el-input) {
    width: 100% !important;
  }

  .org-toolbar :deep(.el-button) {
    min-width: 44px;
    min-height: 44px;
  }

  .org-summary {
    width: 100%;
    margin-left: 0;
  }
}
</style>
