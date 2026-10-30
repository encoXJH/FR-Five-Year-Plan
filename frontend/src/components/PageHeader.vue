<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>
<template><div class="page-heading"><div><h2>{{ title }}</h2><p v-if="description || $slots.description"><slot name="description">{{ description }}</slot></p></div><div class="heading-actions"><slot name="actions" /></div></div></template>
