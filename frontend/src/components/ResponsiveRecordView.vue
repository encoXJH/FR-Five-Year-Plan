<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const isMobile = ref(typeof window !== 'undefined' && window.matchMedia('(max-width: 768px)').matches)
let mediaQuery: MediaQueryList | undefined

function syncMobile(): void {
  isMobile.value = window.matchMedia('(max-width: 768px)').matches
}

onMounted(() => {
  mediaQuery = window.matchMedia('(max-width: 768px)')
  mediaQuery.addEventListener('change', syncMobile)
})

onBeforeUnmount(() => {
  mediaQuery?.removeEventListener('change', syncMobile)
})
</script>

<template>
  <div class="responsive-record">
    <div v-if="!isMobile" class="responsive-record__table">
      <slot name="table" />
    </div>
    <div v-else class="responsive-record__cards">
      <slot name="cards" />
    </div>
  </div>
</template>

<style scoped>
.responsive-record__cards {
  display: none;
}

@media (max-width: 768px) {
  .responsive-record__table {
    display: none;
  }

  .responsive-record__cards {
    display: block;
  }
}
</style>
