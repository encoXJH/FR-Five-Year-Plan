-- 明确的演示数据，生产使用时可跳过本文件；完整包默认包含。
START TRANSACTION;
INSERT INTO wms_partner(partner_id,partner_code,partner_name,partner_type,dept_id) VALUES
(1,'DEMO-FACTORY','演示饮品工厂','FACTORY',100),(2,'DEMO-CUSTOMER','演示经销商','CUSTOMER',100),(3,'DEMO-CARRIER','演示承运商','CARRIER',100);
INSERT INTO wms_product(product_id,product_code,product_name,specification,bottles_per_box,boxes_per_pallet,shelf_life_days,dept_id)
VALUES(1,'DEMO-SKU-001','演示瓶装饮品','500ml × 4瓶/箱',4,1,365,100);
INSERT INTO wms_production_line(line_id,factory_id,line_code,line_name,dept_id) VALUES(1,1,'DEMO-L01','演示灌装线',100);
INSERT INTO wms_batch(batch_id,batch_no,product_id,line_id,production_date,expiry_date,planned_qty,quality_status,dept_id)
VALUES(1,'DEMO-20261103',1,1,'2026-11-03','2027-11-02',4,'PASS',100);
INSERT INTO wms_code_rule(rule_id,rule_name,code_type,prefix,dept_id) VALUES
(1,'演示瓶码','BOTTLE','BT',100),(2,'演示箱码','BOX','BX',100),(3,'演示托盘码','PALLET','PL',100);
INSERT INTO wms_code_job(job_id,job_no,rule_id,batch_id,requested_qty,generated_qty,job_status,dept_id) VALUES
(1,'DEMO-JOB-BT',1,1,4,4,'DONE',100),(2,'DEMO-JOB-BX',2,1,1,1,'DONE',100),(3,'DEMO-JOB-PL',3,1,1,1,'DONE',100);
INSERT INTO wms_code(code_id,code_value,code_type,job_id,batch_id,code_status,activated_at,dept_id) VALUES
(1,'DEMO-BT-0001','BOTTLE',1,1,'ACTIVE','2026-11-03 08:00:00',100),
(2,'DEMO-BT-0002','BOTTLE',1,1,'ACTIVE','2026-11-03 08:00:00',100),
(3,'DEMO-BT-0003','BOTTLE',1,1,'ACTIVE','2026-11-03 08:00:00',100),
(4,'DEMO-BT-0004','BOTTLE',1,1,'ACTIVE','2026-11-03 08:00:00',100),
(5,'DEMO-BX-0001','BOX',2,1,'ACTIVE','2026-11-03 08:00:00',100),
(6,'DEMO-PL-0001','PALLET',3,1,'ACTIVE','2026-11-03 08:00:00',100);
INSERT INTO wms_code_relation(parent_code_id,parent_type,child_code_id,child_type,bound_at,bind_request_id,dept_id) VALUES
(5,'BOX',1,'BOTTLE','2026-11-03 08:10:00','DEMO-BIND-1',100),
(5,'BOX',2,'BOTTLE','2026-11-03 08:10:00','DEMO-BIND-1',100),
(5,'BOX',3,'BOTTLE','2026-11-03 08:10:00','DEMO-BIND-1',100),
(5,'BOX',4,'BOTTLE','2026-11-03 08:10:00','DEMO-BIND-1',100),
(6,'PALLET',5,'BOX','2026-11-03 08:20:00','DEMO-BIND-2',100);
INSERT INTO wms_warehouse(warehouse_id,warehouse_code,warehouse_name,owner_partner_id,dept_id) VALUES(1,'DEMO-WH','演示成品仓',1,100);
INSERT INTO wms_location(location_id,warehouse_id,location_code,location_name,dept_id) VALUES(1,1,'A-01','演示成品库位',100);
INSERT INTO wms_device(device_id,device_code,device_name,device_type,line_id,dept_id) VALUES(1,'DEMO-PDA','演示PDA','PDA',1,100);
INSERT INTO wms_inspection(inspection_id,inspection_no,batch_id,sample_qty,result,items,inspector_id,inspected_at,dept_id)
VALUES(1,'DEMO-QC-001',1,1,'PASS',JSON_ARRAY(JSON_OBJECT('item','包装完整性','result','合格')),1,'2026-11-03 08:30:00',100);
INSERT INTO wms_document(document_id,document_no,document_type,from_warehouse_id,to_warehouse_id,partner_id,carrier_id,logistics_no,document_status,posted_at,dept_id) VALUES
(1,'DEMO-IN-001','PRODUCTION_IN',NULL,1,1,NULL,NULL,'POSTED','2026-11-03 09:00:00',100),
(2,'DEMO-OUT-001','SALE_OUT',1,NULL,2,3,'DEMO-WAYBILL-001','POSTED','2026-11-03 10:00:00',100);
INSERT INTO wms_document_line(line_id,document_id,line_no,product_id,batch_id,from_location_id,to_location_id,quantity,dept_id) VALUES
(1,1,1,1,1,NULL,1,4,100),(2,2,1,1,1,1,NULL,4,100);
INSERT INTO wms_document_code(line_id,code_id,quantity) VALUES(1,6,4),(2,6,4);
INSERT INTO wms_stock_balance(stock_id,location_id,batch_id,quantity,reserved_qty,dept_id) VALUES(1,1,1,0,0,100);
INSERT INTO wms_stock_ledger(line_id,location_id,batch_id,movement,quantity,occurred_at,request_id,operator_id) VALUES
(1,1,1,'IN',4,'2026-11-03 09:00:00','DEMO-POST-IN',1),(2,1,1,'OUT',4,'2026-11-03 10:00:00','DEMO-POST-OUT',1);
INSERT INTO wms_trace_event(event_id,event_no,event_type,occurred_at,document_id,device_id,location_id,from_partner_id,to_partner_id,operator_id,dept_id,source_system,source_event_id,payload) VALUES
(1,'DEMO-E01','PRODUCE','2026-11-03 08:00:00',NULL,1,NULL,1,1,1,100,'DEMO','E01',JSON_OBJECT('batchNo','DEMO-20261103','quantity',4)),
(2,'DEMO-E02','BIND','2026-11-03 08:10:00',NULL,1,NULL,1,1,1,100,'DEMO','E02',JSON_OBJECT('parent','DEMO-BX-0001','bottles',4)),
(3,'DEMO-E03','BIND','2026-11-03 08:20:00',NULL,1,NULL,1,1,1,100,'DEMO','E03',JSON_OBJECT('parent','DEMO-PL-0001','boxes',1)),
(4,'DEMO-E04','INSPECT','2026-11-03 08:30:00',NULL,1,NULL,1,1,1,100,'DEMO','E04',JSON_OBJECT('inspectionNo','DEMO-QC-001','result','PASS')),
(5,'DEMO-E05','INBOUND','2026-11-03 09:00:00',1,1,1,1,1,1,100,'DEMO','E05',JSON_OBJECT('documentNo','DEMO-IN-001','quantity',4)),
(6,'DEMO-E06','OUTBOUND','2026-11-03 10:00:00',2,1,1,1,2,1,100,'DEMO','E06',JSON_OBJECT('documentNo','DEMO-OUT-001','quantity',4,'carrier','演示承运商')),
(7,'DEMO-E07','SIGN','2026-11-03 11:00:00',2,1,NULL,1,2,1,100,'DEMO','E07',JSON_OBJECT('result','经销商签收'));
-- 事件发生时展开保存瓶箱托参与者；拆包后仍能直接按瓶查到原托盘出入库事件。
INSERT INTO wms_event_code(event_id,code_id,code_role) SELECT e.event_id,c.code_id,'SUBJECT' FROM wms_trace_event e CROSS JOIN wms_code c WHERE e.event_id IN(1,4,5,6,7);
INSERT INTO wms_event_code(event_id,code_id,code_role) VALUES
(2,5,'PARENT'),(2,1,'CHILD'),(2,2,'CHILD'),(2,3,'CHILD'),(2,4,'CHILD'),
(3,6,'PARENT'),(3,5,'CHILD'),(3,1,'CHILD'),(3,2,'CHILD'),(3,3,'CHILD'),(3,4,'CHILD');
INSERT INTO wms_inventory_code(code_id,holder_partner_id,inventory_status,last_event_id,dept_id)
VALUES(1,2,'SHIPPED',7,100),(2,2,'SHIPPED',7,100),(3,2,'SHIPPED',7,100),(4,2,'SHIPPED',7,100);
INSERT INTO wms_scan_log(scan_value,code_id,device_id,scan_scene,scan_result,scanned_at,request_id)
VALUES('DEMO-PL-0001',6,1,'WAREHOUSE','VALID','2026-11-03 10:00:00','DEMO-SCAN-001');
COMMIT;
