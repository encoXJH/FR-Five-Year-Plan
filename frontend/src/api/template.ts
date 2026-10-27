import { http } from '@/utils/request'
import type { PlanTemplate, PlanTemplateDetail, UpdateTemplateRequest } from '@/types/plan'

/** 模板列表（后端不分页、无查询参数，状态 / 关键字在前端本地过滤） */
export function listTemplates(): Promise<PlanTemplate[]> {
  return http.get('/templates')
}

export function getTemplate(templateId: string): Promise<PlanTemplateDetail> {
  return http.get(`/templates/${templateId}`)
}

export function updateTemplate(
  templateId: string,
  data: UpdateTemplateRequest,
): Promise<PlanTemplateDetail> {
  return http.patch(`/templates/${templateId}`, data)
}

export function copyTemplate(templateId: string): Promise<PlanTemplateDetail> {
  return http.post(`/templates/${templateId}/copy`)
}

export function disableTemplate(templateId: string): Promise<PlanTemplateDetail> {
  return http.post(`/templates/${templateId}/disable`)
}

export function enableTemplate(templateId: string): Promise<PlanTemplateDetail> {
  return http.post(`/templates/${templateId}/enable`)
}
