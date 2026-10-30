<script setup lang="ts">
defineProps<{
  version?: number
  current?: boolean | number
}>()
</script>
<template><span class="version-tag">V{{ version ?? '—' }}<template v-if="current"> · 当前</template></span></template>
