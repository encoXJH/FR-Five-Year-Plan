import { http } from '@/utils/request'
import type { TrainingSheetEntry } from '@/types/training'

export function listTrainingSheets(planId: string): Promise<TrainingSheetEntry[]> {
  return http.get('/training-sheets', { planId })
}
