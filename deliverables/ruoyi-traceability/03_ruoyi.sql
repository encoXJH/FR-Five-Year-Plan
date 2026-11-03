-- 适配官方 RuoYi-Vue 系统表；不适用于 RuoYi-Plus / Cloud / 芋道的不同表结构。
-- 预留菜单ID 880000-880999；已有库请先检查是否占用。
-- 菜单默认隐藏且停用：数据库包不包含对应页面与接口。完成应用开发后再启用。
START TRANSACTION;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark) VALUES
(880000,'仓储与追溯',0,10,'traceability',NULL,1,1,'M','1','1','','tree','admin',NOW(),'一物一码业务目录，开发完成后启用'),
(880010,'产品档案',880000,1,'product','wms/product/index',1,1,'C','1','1','wms:product:list','list','admin',NOW(),''),
(880020,'生产批次',880000,2,'batch','wms/batch/index',1,1,'C','1','1','wms:batch:list','list','admin',NOW(),''),
(880030,'码管理',880000,3,'code','wms/code/index',1,1,'C','1','1','wms:code:list','list','admin',NOW(),''),
(880040,'包装关联',880000,4,'relation','wms/relation/index',1,1,'C','1','1','wms:relation:list','tree','admin',NOW(),''),
(880050,'仓储单据',880000,5,'document','wms/document/index',1,1,'C','1','1','wms:document:list','list','admin',NOW(),''),
(880060,'库存查询',880000,6,'stock','wms/stock/index',1,1,'C','1','1','wms:stock:list','list','admin',NOW(),''),
(880070,'全链路追溯',880000,7,'trace','wms/trace/index',1,1,'C','1','1','wms:trace:query','search','admin',NOW(),''),
(880080,'质量检验',880000,8,'inspection','wms/inspection/index',1,1,'C','1','1','wms:inspection:list','list','admin',NOW(),''),
(880090,'批次召回',880000,9,'recall','wms/recall/index',1,1,'C','1','1','wms:recall:list','list','admin',NOW(),'');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time) VALUES
(880011,'产品查询',880010,1,'#','F','1','1','wms:product:query','admin',NOW()),
(880012,'产品新增',880010,2,'#','F','1','1','wms:product:add','admin',NOW()),
(880013,'产品修改',880010,3,'#','F','1','1','wms:product:edit','admin',NOW()),
(880021,'批次新增',880020,1,'#','F','1','1','wms:batch:add','admin',NOW()),
(880031,'批量生码',880030,1,'#','F','1','1','wms:code:generate','admin',NOW()),
(880032,'码作废',880030,2,'#','F','1','1','wms:code:void','admin',NOW()),
(880033,'码导出',880030,3,'#','F','1','1','wms:code:export','admin',NOW()),
(880041,'装箱装托',880040,1,'#','F','1','1','wms:relation:bind','admin',NOW()),
(880042,'拆箱拆托',880040,2,'#','F','1','1','wms:relation:unbind','admin',NOW()),
(880051,'单据新增',880050,1,'#','F','1','1','wms:document:add','admin',NOW()),
(880052,'单据审核',880050,2,'#','F','1','1','wms:document:approve','admin',NOW()),
(880053,'单据过账',880050,3,'#','F','1','1','wms:document:post','admin',NOW()),
(880061,'库存导出',880060,1,'#','F','1','1','wms:stock:export','admin',NOW()),
(880071,'追溯导出',880070,1,'#','F','1','1','wms:trace:export','admin',NOW()),
(880081,'质量检验',880080,1,'#','F','1','1','wms:inspection:add','admin',NOW()),
(880091,'发起召回',880090,1,'#','F','1','1','wms:recall:add','admin',NOW()),
(880092,'确认回收',880090,2,'#','F','1','1','wms:recall:recover','admin',NOW());
-- 不自动给现有普通角色授权，由管理员按实际职责配置。
INSERT INTO sys_dict_type(dict_name,dict_type,status,create_by,create_time) VALUES
('包装码类型','wms_code_type','0','admin',NOW()),('码状态','wms_code_status','0','admin',NOW()),
('追溯事件类型','wms_event_type','0','admin',NOW()),('仓储单据类型','wms_document_type','0','admin',NOW()),
('单据状态','wms_document_status','0','admin',NOW()),('质检状态','wms_quality_status','0','admin',NOW());
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,list_class,is_default,status,create_by,create_time) VALUES
(1,'瓶码','BOTTLE','wms_code_type','primary','N','0','admin',NOW()),(2,'箱码','BOX','wms_code_type','success','N','0','admin',NOW()),(3,'托盘码','PALLET','wms_code_type','warning','N','0','admin',NOW()),
(1,'新建','CREATED','wms_code_status','info','N','0','admin',NOW()),(2,'已赋码','ACTIVE','wms_code_status','success','N','0','admin',NOW()),(3,'作废','VOID','wms_code_status','danger','N','0','admin',NOW()),
(1,'生产','PRODUCE','wms_event_type','primary','N','0','admin',NOW()),(2,'赋码','ACTIVATE','wms_event_type','primary','N','0','admin',NOW()),
(3,'关联','BIND','wms_event_type','success','N','0','admin',NOW()),(4,'解除关联','UNBIND','wms_event_type','warning','N','0','admin',NOW()),
(5,'质检','INSPECT','wms_event_type','primary','N','0','admin',NOW()),(6,'入库','INBOUND','wms_event_type','success','N','0','admin',NOW()),
(7,'出库','OUTBOUND','wms_event_type','warning','N','0','admin',NOW()),(8,'调拨','TRANSFER','wms_event_type','primary','N','0','admin',NOW()),
(9,'退货','RETURN','wms_event_type','warning','N','0','admin',NOW()),(10,'签收','SIGN','wms_event_type','success','N','0','admin',NOW()),
(11,'消费','CONSUME','wms_event_type','info','N','0','admin',NOW()),(12,'召回','RECALL','wms_event_type','danger','N','0','admin',NOW()),
(13,'销毁','DESTROY','wms_event_type','danger','N','0','admin',NOW()),(14,'更正','CORRECT','wms_event_type','warning','N','0','admin',NOW()),
(1,'生产入库','PRODUCTION_IN','wms_document_type','success','N','0','admin',NOW()),(2,'采购入库','PURCHASE_IN','wms_document_type','success','N','0','admin',NOW()),
(3,'销售出库','SALE_OUT','wms_document_type','warning','N','0','admin',NOW()),(4,'仓间调拨','TRANSFER','wms_document_type','primary','N','0','admin',NOW()),
(5,'退货入库','RETURN_IN','wms_document_type','warning','N','0','admin',NOW()),(6,'库存盘点','COUNT','wms_document_type','info','N','0','admin',NOW()),
(1,'草稿','DRAFT','wms_document_status','info','N','0','admin',NOW()),(2,'已审核','APPROVED','wms_document_status','primary','N','0','admin',NOW()),
(3,'已过账','POSTED','wms_document_status','success','N','0','admin',NOW()),(4,'已取消','CANCELLED','wms_document_status','danger','N','0','admin',NOW()),
(1,'待检','PENDING','wms_quality_status','info','N','0','admin',NOW()),(2,'合格','PASS','wms_quality_status','success','N','0','admin',NOW()),
(3,'冻结','HOLD','wms_quality_status','warning','N','0','admin',NOW()),(4,'不合格','FAIL','wms_quality_status','danger','N','0','admin',NOW());
COMMIT;
