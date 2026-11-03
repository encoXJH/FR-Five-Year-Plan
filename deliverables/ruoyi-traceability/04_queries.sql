SET NAMES utf8mb4;
SET @code = 'DEMO-BT-0001';

-- 1. 查一个码的全部追溯事件。事件与成员快照不依赖当前包装关系。
SELECT c.code_value,e.event_no,e.event_type,e.occurred_at,fp.partner_name from_partner,tp.partner_name to_partner,e.payload
FROM wms_code c JOIN wms_event_code ec ON ec.code_id=c.code_id JOIN wms_trace_event e ON e.event_id=ec.event_id
LEFT JOIN wms_partner fp ON fp.partner_id=e.from_partner_id LEFT JOIN wms_partner tp ON tp.partner_id=e.to_partner_id
WHERE c.code_value=@code ORDER BY e.occurred_at,e.event_id;

-- 2. 按指定时间点查瓶 -> 箱 -> 托盘，拆包前后的结果独立。
SET @at = '2026-11-03 09:00:00.000';
WITH RECURSIVE ancestors AS (
  SELECT code_id,code_value,code_type,0 depth FROM wms_code WHERE code_value=@code
  UNION ALL
  SELECT p.code_id,p.code_value,p.code_type,a.depth+1 FROM ancestors a
  JOIN wms_code_relation r ON r.child_code_id=a.code_id AND r.bound_at<=@at AND (r.unbound_at IS NULL OR @at<r.unbound_at)
  JOIN wms_code p ON p.code_id=r.parent_code_id WHERE a.depth<2
) SELECT * FROM ancestors ORDER BY depth;

-- 3. 托盘展开到所有瓶，按实际关联计算数量，支持混批托盘。
SET @pallet = 'DEMO-PL-0001';
WITH RECURSIVE contents AS (
  SELECT code_id,code_value,code_type,0 depth FROM wms_code WHERE code_value=@pallet
  UNION ALL
  SELECT c.code_id,c.code_value,c.code_type,p.depth+1 FROM contents p
  JOIN wms_code_relation r ON r.parent_code_id=p.code_id AND r.unbound_at IS NULL
  JOIN wms_code c ON c.code_id=r.child_code_id WHERE p.depth<2
) SELECT c.code_value,b.batch_no,p.product_name FROM contents c JOIN wms_code x ON x.code_id=c.code_id
JOIN wms_batch b ON b.batch_id=x.batch_id JOIN wms_product p ON p.product_id=b.product_id WHERE c.code_type='BOTTLE';

-- 4. 召回批次定位到每一瓶的当前持有人。
SELECT c.code_value,b.batch_no,p.partner_name,i.inventory_status,i.location_id
FROM wms_code c JOIN wms_batch b ON b.batch_id=c.batch_id
LEFT JOIN wms_inventory_code i ON i.code_id=c.code_id LEFT JOIN wms_partner p ON p.partner_id=i.holder_partner_id
WHERE b.batch_no='DEMO-20261103' AND c.code_type='BOTTLE';

-- 5. 库存余额与不可变流水对账；正确时返回0行。
SELECT s.stock_id,s.quantity,COALESCE(l.net_qty,0) ledger_qty FROM wms_stock_balance s
LEFT JOIN (SELECT location_id,batch_id,SUM(CASE WHEN movement='IN' THEN quantity ELSE -quantity END) net_qty
FROM wms_stock_ledger GROUP BY location_id,batch_id) l ON l.location_id=s.location_id AND l.batch_id=s.batch_id
WHERE s.quantity<>COALESCE(l.net_qty,0)
UNION ALL
SELECT NULL,0,l.net_qty FROM (SELECT location_id,batch_id,SUM(CASE WHEN movement='IN' THEN quantity ELSE -quantity END) net_qty
FROM wms_stock_ledger GROUP BY location_id,batch_id) l LEFT JOIN wms_stock_balance s ON s.location_id=l.location_id AND s.batch_id=l.batch_id
WHERE s.stock_id IS NULL AND l.net_qty<>0;
