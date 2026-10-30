import { ref, type Ref } from 'vue'
import { errorMessage } from '@/api'

export function useAsyncData<T>(loader: () => Promise<T>, initialValue: T) {
  const data = ref(initialValue) as Ref<T>
  const loading = ref(false)
  const error = ref('')
  async function load() {
    loading.value = true
    error.value = ''
    try {
      data.value = await loader()
      return data.value
    } catch (cause) {
      error.value = errorMessage(cause)
      throw cause
    } finally {
      loading.value = false
    }
  }
  return { data, loading, error, load }
}
