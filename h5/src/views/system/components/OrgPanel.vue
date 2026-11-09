<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import PageState from '@/components/PageState.vue'
import { getOrgDepartmentTree } from '@/api/system'
import type { OrgDepartmentNode } from '@/types/system'
import { useUserStore } from '@/stores/user'
import { enumLabel } from '@/utils/enumLabels'

const userStore = useUserStore()
const loading = ref(false)
const tree = ref<OrgDepartmentNode[]>([])

interface FlatNode extends OrgDepartmentNode {
  depth: number
}

const flatNodes = computed<FlatNode[]>(() => {
  const result: FlatNode[] = []
  const walk = (nodes: OrgDepartmentNode[], depth: number): void => {
    for (const node of nodes) {
      result.push({ ...node, depth })
      if (node.children?.length) walk(node.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return result
})

onMounted(async () => {
  loading.value = true
  try {
    tree.value = await getOrgDepartmentTree()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <div class="panel-head">
      <h2>组织架构</h2>
      <span class="small muted">只读，来源于飞书组织同步</span>
    </div>
    <PageState :loading="loading" :empty="flatNodes.length === 0" empty-text="暂无组织架构数据">
      <div class="org-list">
        <div
          v-for="node in flatNodes"
          :key="node.id"
          class="org-item"
          :style="{ paddingLeft: `${10 + node.depth * 16}px` }"
        >
          <div class="row">
            <van-icon v-if="node.depth === 0" name="cluster-o" />
            <span>{{ node.name }}</span>
            <span class="chip chip--gray">{{ enumLabel('OrgDepartmentCategory', node.category, userStore.enums) }}</span>
            <span v-if="!node.isEnabled" class="chip chip--danger">停用</span>
          </div>
        </div>
      </div>
    </PageState>
  </div>
</template>

<style scoped>
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.panel-head h2 {
  margin: 0;
  font-size: 18px;
}
.org-list {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  overflow: hidden;
}
.org-item {
  padding-top: 10px;
  padding-bottom: 10px;
  padding-right: 10px;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
}
.org-item:last-child {
  border-bottom: none;
}
.org-item .row {
  flex-wrap: wrap;
}
</style>
