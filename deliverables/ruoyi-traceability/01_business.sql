-- 业务模型源文件；直接导入请使用生成的 01_business.sql。
SET NAMES utf8mb4;
SET time_zone = '+08:00';

CREATE TABLE wms_partner (
  partner_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '往来单位ID',
  partner_code VARCHAR(64) NOT NULL COMMENT '单位编码',
  partner_name VARCHAR(200) NOT NULL COMMENT '单位名称',
  partner_type VARCHAR(16) NOT NULL COMMENT 'FACTORY工厂 SUPPLIER供应商 CUSTOMER客户 CARRIER承运商',
  contact_name VARCHAR(64) NULL COMMENT '联系人',
  contact_phone VARCHAR(32) NULL COMMENT '联系电话',
  address VARCHAR(500) NULL COMMENT '地址',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (partner_id), UNIQUE KEY uk_partner_code (partner_code), KEY idx_partner_dept (dept_id),
  CHECK (partner_type IN ('FACTORY','SUPPLIER','CUSTOMER','CARRIER')), CHECK (status IN ('0','1'))
) ENGINE=InnoDB COMMENT='工厂与供应链往来单位';

CREATE TABLE wms_product (
  product_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '产品ID',
  product_code VARCHAR(64) NOT NULL COMMENT 'SKU编码',
  product_name VARCHAR(200) NOT NULL COMMENT '产品名称',
  gtin VARCHAR(14) NULL COMMENT 'GS1商品条码，保留前导零',
  specification VARCHAR(200) NULL COMMENT '规格',
  base_unit VARCHAR(16) NOT NULL DEFAULT '瓶' COMMENT '库存基本单位',
  bottles_per_box INT NOT NULL COMMENT '标准每箱瓶数，实际关系以关联表为准',
  boxes_per_pallet INT NOT NULL COMMENT '标准每托箱数',
  shelf_life_days INT NOT NULL COMMENT '保质期天数',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (product_id), UNIQUE KEY uk_product_code (product_code), KEY idx_product_dept (dept_id),
  CHECK (bottles_per_box > 0 AND boxes_per_pallet > 0 AND shelf_life_days > 0), CHECK (status IN ('0','1'))
) ENGINE=InnoDB COMMENT='产品档案';

CREATE TABLE wms_production_line (
  line_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '产线ID',
  factory_id BIGINT NOT NULL COMMENT '所属工厂ID',
  line_code VARCHAR(64) NOT NULL COMMENT '产线编码',
  line_name VARCHAR(100) NOT NULL COMMENT '产线名称',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (line_id), UNIQUE KEY uk_line_code (line_code), KEY idx_line_factory (factory_id), KEY idx_line_dept (dept_id),
  FOREIGN KEY (factory_id) REFERENCES wms_partner(partner_id), CHECK (status IN ('0','1'))
) ENGINE=InnoDB COMMENT='生产产线';

CREATE TABLE wms_batch (
  batch_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '生产批次ID',
  batch_no VARCHAR(64) NOT NULL COMMENT '批号',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  line_id BIGINT NOT NULL COMMENT '产线ID',
  production_date DATE NOT NULL COMMENT '生产日期',
  expiry_date DATE NOT NULL COMMENT '有效截止日期',
  planned_qty DECIMAL(20,6) NOT NULL COMMENT '计划基本单位数量',
  quality_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待检 PASS合格 HOLD冻结 FAIL不合格',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (batch_id), UNIQUE KEY uk_batch_no (batch_no), UNIQUE KEY uk_batch_product (batch_id,product_id),
  KEY idx_batch_product (product_id), KEY idx_batch_line (line_id), KEY idx_batch_expiry (expiry_date), KEY idx_batch_dept (dept_id),
  FOREIGN KEY (product_id) REFERENCES wms_product(product_id), FOREIGN KEY (line_id) REFERENCES wms_production_line(line_id),
  CHECK (expiry_date >= production_date AND planned_qty > 0), CHECK (quality_status IN ('PENDING','PASS','HOLD','FAIL'))
) ENGINE=InnoDB COMMENT='生产批次';

CREATE TABLE wms_code_rule (
  rule_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '码规则ID',
  rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',
  code_type VARCHAR(16) NOT NULL COMMENT 'BOTTLE瓶 BOX箱 PALLET托盘',
  prefix VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '码前缀',
  random_length INT NOT NULL DEFAULT 24 COMMENT '安全随机段长度，由应用生成',
  rule_version INT NOT NULL DEFAULT 1 COMMENT '规则修订号，已发码不重算',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (rule_id), UNIQUE KEY uk_code_rule (prefix,rule_version), KEY idx_rule_dept (dept_id),
  CHECK (code_type IN ('BOTTLE','BOX','PALLET')), CHECK (random_length BETWEEN 16 AND 64), CHECK (status IN ('0','1'))
) ENGINE=InnoDB COMMENT='一物一码编码规则';

CREATE TABLE wms_code_job (
  job_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '赋码任务ID',
  job_no VARCHAR(64) NOT NULL COMMENT '赋码任务号',
  rule_id BIGINT NOT NULL COMMENT '规则ID',
  batch_id BIGINT NOT NULL COMMENT '批次ID',
  requested_qty BIGINT NOT NULL COMMENT '申请码数',
  generated_qty BIGINT NOT NULL DEFAULT 0 COMMENT '已生成码数',
  job_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待生成 RUNNING生成中 DONE完成 FAILED失败',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (job_id), UNIQUE KEY uk_job_no (job_no), KEY idx_job_rule (rule_id), KEY idx_job_batch (batch_id), KEY idx_job_dept (dept_id),
  FOREIGN KEY (rule_id) REFERENCES wms_code_rule(rule_id), FOREIGN KEY (batch_id) REFERENCES wms_batch(batch_id),
  CHECK (requested_qty > 0 AND generated_qty BETWEEN 0 AND requested_qty), CHECK (job_status IN ('PENDING','RUNNING','DONE','FAILED'))
) ENGINE=InnoDB COMMENT='批量生码与赋码任务';

CREATE TABLE wms_code (
  code_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '唯一码ID',
  code_value VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全局唯一码，区分大小写',
  code_type VARCHAR(16) NOT NULL COMMENT 'BOTTLE瓶 BOX箱 PALLET托盘',
  job_id BIGINT NOT NULL COMMENT '生码任务ID',
  batch_id BIGINT NOT NULL COMMENT '所属批次，托盘为首批次，其内容以关联关系为准',
  code_status VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED新建 ACTIVE已赋码 VOID作废',
  activated_at DATETIME(3) NULL COMMENT '赋码时间',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (code_id), UNIQUE KEY uk_code_value (code_value), UNIQUE KEY uk_code_type (code_id,code_type),
  KEY idx_code_job (job_id), KEY idx_code_batch_type (batch_id,code_type), KEY idx_code_dept (dept_id),
  FOREIGN KEY (job_id) REFERENCES wms_code_job(job_id), FOREIGN KEY (batch_id) REFERENCES wms_batch(batch_id),
  CHECK (code_type IN ('BOTTLE','BOX','PALLET')), CHECK (code_status IN ('CREATED','ACTIVE','VOID'))
) ENGINE=InnoDB COMMENT='瓶码箱码托盘码主档，永久禁止复用';

CREATE TABLE wms_code_relation (
  relation_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关联历史ID',
  parent_code_id BIGINT NOT NULL COMMENT '父包装码ID',
  parent_type VARCHAR(16) NOT NULL COMMENT '父包装类型',
  child_code_id BIGINT NOT NULL COMMENT '子包装码ID',
  child_type VARCHAR(16) NOT NULL COMMENT '子包装类型',
  bound_at DATETIME(3) NOT NULL COMMENT '绑定时间，包含端点',
  unbound_at DATETIME(3) NULL COMMENT '解绑时间，不包含端点；NULL为当前有效',
  bind_request_id VARCHAR(64) NOT NULL COMMENT '绑定操作请求ID',
  unbind_reason VARCHAR(500) NULL COMMENT '拆箱拆托原因',
  active_child_id BIGINT GENERATED ALWAYS AS (CASE WHEN unbound_at IS NULL THEN child_code_id ELSE NULL END) STORED COMMENT '唯一有效父节点约束',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (relation_id), UNIQUE KEY uk_relation_active_child (active_child_id),
  KEY idx_relation_parent (parent_code_id,bound_at,unbound_at), KEY idx_relation_child (child_code_id,bound_at,unbound_at), KEY idx_relation_dept (dept_id),
  FOREIGN KEY (parent_code_id,parent_type) REFERENCES wms_code(code_id,code_type),
  FOREIGN KEY (child_code_id,child_type) REFERENCES wms_code(code_id,code_type),
  CHECK ((parent_type='BOX' AND child_type='BOTTLE') OR (parent_type='PALLET' AND child_type='BOX')),
  CHECK (parent_code_id <> child_code_id), CHECK (unbound_at IS NULL OR unbound_at > bound_at),
  CHECK (unbound_at IS NULL OR (unbind_reason IS NOT NULL AND CHAR_LENGTH(TRIM(unbind_reason)) > 0))
) ENGINE=InnoDB COMMENT='包装关联历史，一子一码只能存在一个有效父码';

CREATE TABLE wms_warehouse (
  warehouse_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '仓库ID',
  warehouse_code VARCHAR(64) NOT NULL COMMENT '仓库编码',
  warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称',
  owner_partner_id BIGINT NOT NULL COMMENT '所属往来单位ID',
  address VARCHAR(500) NULL COMMENT '仓库地址',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (warehouse_id), UNIQUE KEY uk_warehouse_code (warehouse_code), KEY idx_warehouse_owner (owner_partner_id), KEY idx_warehouse_dept (dept_id),
  FOREIGN KEY (owner_partner_id) REFERENCES wms_partner(partner_id), CHECK (status IN ('0','1'))
) ENGINE=InnoDB COMMENT='仓库档案';

CREATE TABLE wms_location (
  location_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '库位ID',
  warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
  location_code VARCHAR(64) NOT NULL COMMENT '仓内库位编码',
  location_name VARCHAR(100) NOT NULL COMMENT '库位名称',
  location_type VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL正常 HOLD隔离 RETURN退货',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (location_id), UNIQUE KEY uk_location_code (warehouse_id,location_code), KEY idx_location_dept (dept_id),
  FOREIGN KEY (warehouse_id) REFERENCES wms_warehouse(warehouse_id), CHECK (location_type IN ('NORMAL','HOLD','RETURN')), CHECK (status IN ('0','1'))
) ENGINE=InnoDB COMMENT='仓库库位';

CREATE TABLE wms_document (
  document_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '仓储单据ID',
  document_no VARCHAR(64) NOT NULL COMMENT '单据编号',
  document_type VARCHAR(20) NOT NULL COMMENT 'PRODUCTION_IN生产入库 PURCHASE_IN采购入库 SALE_OUT销售出库 TRANSFER调拨 RETURN_IN退货 COUNT盘点',
  from_warehouse_id BIGINT NULL COMMENT '来源仓库ID',
  to_warehouse_id BIGINT NULL COMMENT '目标仓库ID',
  partner_id BIGINT NULL COMMENT '客户或供应商ID',
  carrier_id BIGINT NULL COMMENT '承运商ID',
  logistics_no VARCHAR(100) NULL COMMENT '物流单号',
  source_no VARCHAR(100) NULL COMMENT '外部业务单号',
  document_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT草稿 APPROVED审核 POSTED过账 CANCELLED取消',
  posted_at DATETIME(3) NULL COMMENT '过账时间',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (document_id), UNIQUE KEY uk_document_no (document_no), KEY idx_document_from (from_warehouse_id),
  KEY idx_document_to (to_warehouse_id), KEY idx_document_partner (partner_id), KEY idx_document_carrier (carrier_id), KEY idx_document_dept (dept_id),
  FOREIGN KEY (from_warehouse_id) REFERENCES wms_warehouse(warehouse_id), FOREIGN KEY (to_warehouse_id) REFERENCES wms_warehouse(warehouse_id),
  FOREIGN KEY (partner_id) REFERENCES wms_partner(partner_id), FOREIGN KEY (carrier_id) REFERENCES wms_partner(partner_id),
  CHECK (document_type IN ('PRODUCTION_IN','PURCHASE_IN','SALE_OUT','TRANSFER','RETURN_IN','COUNT')),
  CHECK (document_status IN ('DRAFT','APPROVED','POSTED','CANCELLED')),
  CHECK (document_type <> 'TRANSFER' OR (from_warehouse_id IS NOT NULL AND to_warehouse_id IS NOT NULL AND from_warehouse_id <> to_warehouse_id))
) ENGINE=InnoDB COMMENT='入库出库调拨退货盘点单据';

CREATE TABLE wms_document_line (
  line_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '单据明细ID',
  document_id BIGINT NOT NULL COMMENT '单据ID',
  line_no INT NOT NULL COMMENT '单内行号',
  product_id BIGINT NOT NULL COMMENT '产品ID',
  batch_id BIGINT NOT NULL COMMENT '批次ID',
  from_location_id BIGINT NULL COMMENT '来源库位ID',
  to_location_id BIGINT NULL COMMENT '目标库位ID',
  quantity DECIMAL(20,6) NOT NULL COMMENT '基本单位数量，盘点差异用流水表达',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (line_id), UNIQUE KEY uk_document_line (document_id,line_no), KEY idx_docline_batch_product (batch_id,product_id),
  KEY idx_docline_from (from_location_id), KEY idx_docline_to (to_location_id), KEY idx_docline_dept (dept_id),
  FOREIGN KEY (document_id) REFERENCES wms_document(document_id), FOREIGN KEY (batch_id,product_id) REFERENCES wms_batch(batch_id,product_id),
  FOREIGN KEY (from_location_id) REFERENCES wms_location(location_id), FOREIGN KEY (to_location_id) REFERENCES wms_location(location_id), CHECK (quantity > 0)
) ENGINE=InnoDB COMMENT='仓储单据明细';

CREATE TABLE wms_document_code (
  line_id BIGINT NOT NULL COMMENT '单据明细ID',
  code_id BIGINT NOT NULL COMMENT '操作扫描码ID，同一瓶在单据中只能计数一次，由服务展开去重',
  quantity DECIMAL(20,6) NOT NULL COMMENT '扫描时包装包含基本单位数量快照',
  PRIMARY KEY (line_id,code_id), KEY idx_doccode_code (code_id),
  FOREIGN KEY (line_id) REFERENCES wms_document_line(line_id), FOREIGN KEY (code_id) REFERENCES wms_code(code_id), CHECK (quantity > 0)
) ENGINE=InnoDB COMMENT='仓储单据扫描包装码';

CREATE TABLE wms_stock_balance (
  stock_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '库存余额ID',
  location_id BIGINT NOT NULL COMMENT '库位ID',
  batch_id BIGINT NOT NULL COMMENT '批次ID',
  quantity DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '基本单位在库数量',
  reserved_qty DECIMAL(20,6) NOT NULL DEFAULT 0 COMMENT '基本单位预占数量',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (stock_id), UNIQUE KEY uk_stock_balance (location_id,batch_id), KEY idx_stock_batch (batch_id), KEY idx_stock_dept (dept_id),
  FOREIGN KEY (location_id) REFERENCES wms_location(location_id), FOREIGN KEY (batch_id) REFERENCES wms_batch(batch_id),
  CHECK (quantity >= 0 AND reserved_qty >= 0 AND reserved_qty <= quantity)
) ENGINE=InnoDB COMMENT='库存余额，只统计基本单位瓶，不能与箱托数量叠加';

CREATE TABLE wms_stock_ledger (
  ledger_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '库存流水ID',
  line_id BIGINT NOT NULL COMMENT '单据明细ID',
  location_id BIGINT NOT NULL COMMENT '库位ID',
  batch_id BIGINT NOT NULL COMMENT '批次ID',
  movement VARCHAR(8) NOT NULL COMMENT 'IN增加 OUT减少',
  quantity DECIMAL(20,6) NOT NULL COMMENT '正数变动量',
  occurred_at DATETIME(3) NOT NULL COMMENT '发生时间',
  request_id VARCHAR(64) NOT NULL COMMENT '操作请求ID',
  operator_id BIGINT NULL COMMENT '若依用户ID',
  PRIMARY KEY (ledger_id), UNIQUE KEY uk_ledger_posting (line_id,location_id,movement), KEY idx_ledger_batch (batch_id,occurred_at), KEY idx_ledger_location (location_id,occurred_at),
  FOREIGN KEY (line_id) REFERENCES wms_document_line(line_id), FOREIGN KEY (location_id) REFERENCES wms_location(location_id),
  FOREIGN KEY (batch_id) REFERENCES wms_batch(batch_id), CHECK (movement IN ('IN','OUT')), CHECK (quantity > 0)
) ENGINE=InnoDB COMMENT='库存流水，只追加，红冲用反向单据';

CREATE TABLE wms_inventory_code (
  code_id BIGINT NOT NULL COMMENT '在库瓶码ID，箱托不是库存计数单位',
  code_type VARCHAR(16) NOT NULL DEFAULT 'BOTTLE' COMMENT '固定BOTTLE',
  location_id BIGINT NULL COMMENT '当前库位，离库为NULL',
  holder_partner_id BIGINT NOT NULL COMMENT '当前持有人或货主单位ID',
  inventory_status VARCHAR(16) NOT NULL COMMENT 'IN_STOCK在库 SHIPPED已发货 HOLD隔离 CONSUMED消费 DESTROYED销毁',
  last_event_id BIGINT NULL COMMENT '最近事件ID，逻辑引用追溯事件',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (code_id), KEY idx_inventory_location (location_id,inventory_status), KEY idx_inventory_holder (holder_partner_id), KEY idx_inventory_event (last_event_id), KEY idx_inventory_dept (dept_id),
  FOREIGN KEY (code_id,code_type) REFERENCES wms_code(code_id,code_type), FOREIGN KEY (location_id) REFERENCES wms_location(location_id), FOREIGN KEY (holder_partner_id) REFERENCES wms_partner(partner_id),
  CHECK (code_type='BOTTLE'), CHECK (inventory_status IN ('IN_STOCK','SHIPPED','HOLD','CONSUMED','DESTROYED')),
  CHECK ((inventory_status IN ('IN_STOCK','HOLD') AND location_id IS NOT NULL) OR (inventory_status IN ('SHIPPED','CONSUMED','DESTROYED') AND location_id IS NULL))
) ENGINE=InnoDB COMMENT='单瓶当前库存位置与流向快照';

CREATE TABLE wms_device (
  device_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '设备ID',
  device_code VARCHAR(64) NOT NULL COMMENT '设备编码',
  device_name VARCHAR(100) NOT NULL COMMENT '设备名称',
  device_type VARCHAR(16) NOT NULL COMMENT 'PRINTER打印 CAMERA视觉 PDA手持 SCANNER扫码',
  line_id BIGINT NULL COMMENT '所属产线ID',
  credential_hash VARCHAR(128) NULL COMMENT '接入凭证哈希，不保存明文密钥',
  status CHAR(1) NOT NULL DEFAULT '0' COMMENT '0启用 1停用',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (device_id), UNIQUE KEY uk_device_code (device_code), KEY idx_device_line (line_id), KEY idx_device_dept (dept_id),
  FOREIGN KEY (line_id) REFERENCES wms_production_line(line_id), CHECK (device_type IN ('PRINTER','CAMERA','PDA','SCANNER')), CHECK (status IN ('0','1'))
) ENGINE=InnoDB COMMENT='产线及仓储采集设备';

CREATE TABLE wms_trace_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '追溯事件ID',
  event_no VARCHAR(64) NOT NULL COMMENT '事件编号',
  event_type VARCHAR(24) NOT NULL COMMENT '事件类型，字典wms_event_type',
  occurred_at DATETIME(3) NOT NULL COMMENT '业务发生时间',
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '服务器接收时间',
  document_id BIGINT NULL COMMENT '仓储单据ID',
  device_id BIGINT NULL COMMENT '采集设备ID',
  location_id BIGINT NULL COMMENT '事件库位ID',
  from_partner_id BIGINT NULL COMMENT '来源单位ID',
  to_partner_id BIGINT NULL COMMENT '目标单位ID',
  operator_id BIGINT NULL COMMENT '若依操作用户ID',
  dept_id BIGINT NULL COMMENT '数据范围部门ID',
  source_system VARCHAR(64) NOT NULL COMMENT '来源系统',
  source_event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源事件唯一键',
  payload JSON NOT NULL COMMENT '事件发生时业务快照，禁止保存密钥',
  correction_of BIGINT NULL COMMENT '被更正的原事件ID，通过新事件纠错',
  PRIMARY KEY (event_id), UNIQUE KEY uk_event_no (event_no), UNIQUE KEY uk_event_source (source_system,source_event_id),
  KEY idx_event_time (occurred_at), KEY idx_event_document (document_id), KEY idx_event_device (device_id), KEY idx_event_location (location_id),
  KEY idx_event_from (from_partner_id), KEY idx_event_to (to_partner_id), KEY idx_event_dept (dept_id,occurred_at), KEY idx_event_correction (correction_of),
  FOREIGN KEY (document_id) REFERENCES wms_document(document_id), FOREIGN KEY (device_id) REFERENCES wms_device(device_id),
  FOREIGN KEY (location_id) REFERENCES wms_location(location_id), FOREIGN KEY (from_partner_id) REFERENCES wms_partner(partner_id), FOREIGN KEY (to_partner_id) REFERENCES wms_partner(partner_id),
  FOREIGN KEY (correction_of) REFERENCES wms_trace_event(event_id),
  CHECK (event_type IN ('PRODUCE','ACTIVATE','BIND','UNBIND','INSPECT','INBOUND','OUTBOUND','TRANSFER','RETURN','SIGN','CONSUME','RECALL','DESTROY','CORRECT'))
) ENGINE=InnoDB COMMENT='全链路追溯事件，只追加不可改删';

CREATE TABLE wms_event_code (
  event_id BIGINT NOT NULL COMMENT '事件ID',
  code_id BIGINT NOT NULL COMMENT '参与事件的码ID',
  code_role VARCHAR(16) NOT NULL DEFAULT 'SUBJECT' COMMENT 'SUBJECT对象 PARENT父码 CHILD子码',
  PRIMARY KEY (event_id,code_id), KEY idx_eventcode_code (code_id,event_id),
  FOREIGN KEY (event_id) REFERENCES wms_trace_event(event_id), FOREIGN KEY (code_id) REFERENCES wms_code(code_id), CHECK (code_role IN ('SUBJECT','PARENT','CHILD'))
) ENGINE=InnoDB COMMENT='事件涉及码，历史包装成员须在发生时展开写入';

CREATE TABLE wms_inspection (
  inspection_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '质检ID',
  inspection_no VARCHAR(64) NOT NULL COMMENT '质检单号',
  batch_id BIGINT NOT NULL COMMENT '批次ID',
  sample_qty INT NOT NULL COMMENT '抽样数量',
  result VARCHAR(16) NOT NULL COMMENT 'PASS合格 FAIL不合格',
  items JSON NOT NULL COMMENT '检验项目、实测与标准快照',
  inspector_id BIGINT NOT NULL COMMENT '若依检验员ID',
  inspected_at DATETIME(3) NOT NULL COMMENT '检验时间',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (inspection_id), UNIQUE KEY uk_inspection_no (inspection_no), KEY idx_inspection_batch (batch_id), KEY idx_inspection_dept (dept_id),
  FOREIGN KEY (batch_id) REFERENCES wms_batch(batch_id), CHECK (sample_qty > 0), CHECK (result IN ('PASS','FAIL'))
) ENGINE=InnoDB COMMENT='生产与仓储质量检验';

CREATE TABLE wms_recall (
  recall_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '召回ID',
  recall_no VARCHAR(64) NOT NULL COMMENT '召回单号',
  batch_id BIGINT NOT NULL COMMENT '召回批次ID',
  reason VARCHAR(1000) NOT NULL COMMENT '召回原因',
  recall_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT草稿 ACTIVE执行 CLOSED关闭',
  started_at DATETIME(3) NULL COMMENT '启动时间',
  closed_at DATETIME(3) NULL COMMENT '关闭时间',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (recall_id), UNIQUE KEY uk_recall_no (recall_no), KEY idx_recall_batch (batch_id), KEY idx_recall_dept (dept_id),
  FOREIGN KEY (batch_id) REFERENCES wms_batch(batch_id), CHECK (recall_status IN ('DRAFT','ACTIVE','CLOSED')), CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE=InnoDB COMMENT='批次召回单';

CREATE TABLE wms_recall_code (
  recall_id BIGINT NOT NULL COMMENT '召回单ID',
  code_id BIGINT NOT NULL COMMENT '召回瓶码ID',
  code_type VARCHAR(16) NOT NULL DEFAULT 'BOTTLE' COMMENT '固定BOTTLE',
  recall_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待回收 RECOVERED已回收 DESTROYED已销毁',
  recovered_at DATETIME(3) NULL COMMENT '回收时间',
  PRIMARY KEY (recall_id,code_id), KEY idx_recallcode_code (code_id),
  FOREIGN KEY (recall_id) REFERENCES wms_recall(recall_id), FOREIGN KEY (code_id,code_type) REFERENCES wms_code(code_id,code_type),
  CHECK (code_type='BOTTLE'), CHECK (recall_status IN ('PENDING','RECOVERED','DESTROYED'))
) ENGINE=InnoDB COMMENT='召回范围瓶码清单与回收进度';

CREATE TABLE wms_scan_log (
  scan_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '扫码日志ID',
  scan_value VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始扫码内容，允许未知码',
  code_id BIGINT NULL COMMENT '匹配到的码ID',
  device_id BIGINT NULL COMMENT '扫码设备ID',
  scan_scene VARCHAR(32) NOT NULL COMMENT 'PRODUCTION WAREHOUSE DISTRIBUTION CONSUMER',
  scan_result VARCHAR(16) NOT NULL COMMENT 'VALID UNKNOWN VOID SUSPECT',
  scanned_at DATETIME(3) NOT NULL COMMENT '扫描时间',
  request_id VARCHAR(64) NOT NULL COMMENT '请求ID',
  ip_address VARCHAR(45) NULL COMMENT '来源IP，按隐私策略保留',
  PRIMARY KEY (scan_id), UNIQUE KEY uk_scan_request (request_id), KEY idx_scan_code_time (code_id,scanned_at), KEY idx_scan_device (device_id),
  FOREIGN KEY (code_id) REFERENCES wms_code(code_id), FOREIGN KEY (device_id) REFERENCES wms_device(device_id),
  CHECK (scan_scene IN ('PRODUCTION','WAREHOUSE','DISTRIBUTION','CONSUMER')), CHECK (scan_result IN ('VALID','UNKNOWN','VOID','SUSPECT'))
) ENGINE=InnoDB COMMENT='扫码验证及疑似重复码记录';

CREATE TABLE wms_idempotency (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '幂等记录ID',
  client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '接入端标识',
  operation VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '业务操作',
  idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '幂等请求键',
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求体SHA256，相同键不同请求应拒绝',
  status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING执行中 SUCCEEDED成功 FAILED失败',
  response_json JSON NULL COMMENT '首次执行响应',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  expires_at DATETIME(3) NOT NULL COMMENT '到期时间；过账单号与来源事件唯一约束永久保留',
  PRIMARY KEY (id), UNIQUE KEY uk_idempotency (client_id,operation,idempotency_key), KEY idx_idem_expiry (expires_at),
  CHECK (status IN ('PROCESSING','SUCCEEDED','FAILED')), CHECK (expires_at > created_at)
) ENGINE=InnoDB COMMENT='设备回传及仓储过账幂等记录';

CREATE TABLE wms_attachment (
  attachment_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '附件ID',
  event_id BIGINT NULL COMMENT '关联事件ID',
  inspection_id BIGINT NULL COMMENT '关联质检单ID',
  file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  storage_key VARCHAR(500) NOT NULL COMMENT 'StorageService内部存储键，不存公网签名地址',
  sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文件摘要',
  size_bytes BIGINT NOT NULL COMMENT '字节数',
  
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'
  ,PRIMARY KEY (attachment_id), KEY idx_attachment_event (event_id), KEY idx_attachment_inspection (inspection_id), KEY idx_attachment_dept (dept_id),
  FOREIGN KEY (event_id) REFERENCES wms_trace_event(event_id), FOREIGN KEY (inspection_id) REFERENCES wms_inspection(inspection_id),
  CHECK (size_bytes > 0), CHECK ((event_id IS NOT NULL AND inspection_id IS NULL) OR (event_id IS NULL AND inspection_id IS NOT NULL))
) ENGINE=InnoDB COMMENT='追溯与质检佐证附件';

-- 单语句触发器，兼容 Navicat、Workbench 和 mysql 客户端，无需 DELIMITER。
CREATE TRIGGER wms_trace_event_no_update BEFORE UPDATE ON wms_trace_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Trace events are append-only';
CREATE TRIGGER wms_trace_event_no_delete BEFORE DELETE ON wms_trace_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Trace events are append-only';
CREATE TRIGGER wms_event_code_no_update BEFORE UPDATE ON wms_event_code FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Event membership is append-only';
CREATE TRIGGER wms_event_code_no_delete BEFORE DELETE ON wms_event_code FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Event membership is append-only';
CREATE TRIGGER wms_stock_ledger_no_update BEFORE UPDATE ON wms_stock_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Stock ledger is append-only';
CREATE TRIGGER wms_stock_ledger_no_delete BEFORE DELETE ON wms_stock_ledger FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Stock ledger is append-only';

CREATE VIEW wms_v_current_pack AS
SELECT r.relation_id,p.code_value parent_code,p.code_type parent_type,c.code_value child_code,c.code_type child_type,r.bound_at
FROM wms_code_relation r JOIN wms_code p ON p.code_id=r.parent_code_id JOIN wms_code c ON c.code_id=r.child_code_id
WHERE r.unbound_at IS NULL;

CREATE VIEW wms_v_stock AS
SELECT s.stock_id,w.warehouse_code,l.location_code,p.product_code,p.product_name,b.batch_no,b.expiry_date,
s.quantity,s.reserved_qty,s.quantity-s.reserved_qty available_qty,s.dept_id
FROM wms_stock_balance s JOIN wms_location l ON l.location_id=s.location_id JOIN wms_warehouse w ON w.warehouse_id=l.warehouse_id
JOIN wms_batch b ON b.batch_id=s.batch_id JOIN wms_product p ON p.product_id=b.product_id;
