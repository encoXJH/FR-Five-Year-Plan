-- 材料库数据库分页与常用筛选索引
CREATE INDEX idx_file_info_material_page
    ON file_info (deleted, uploaded_at DESC, id DESC);
CREATE INDEX idx_file_info_material_category
    ON file_info (deleted, category, uploaded_at DESC);
CREATE INDEX idx_task_material_scope
    ON task (top_indicator_id, unit_id, deleted);
