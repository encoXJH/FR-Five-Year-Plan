import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
const dir = dirname(fileURLToPath(import.meta.url))
const read = p => readFileSync(join(dir, p), 'utf8')
const audit = `
  dept_id BIGINT NULL COMMENT '若依部门ID，数据权限范围',
  create_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '创建者',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  update_by VARCHAR(64) NOT NULL DEFAULT '' COMMENT '更新者',
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  remark VARCHAR(500) NULL COMMENT '备注'`
let business = read('schema.sql').replaceAll('-- @audit', audit)
writeFileSync(join(dir, '01_business.sql'), business)
const clean = text => text.replace(/^\s*DROP TABLE IF EXISTS[^;]+;\s*$/gim, '')
const header = `-- 一物一码追溯平台；MySQL >= 8.0.16；UTF-8
-- 仅用于新库初始化。已有若依库请只执行 01_business.sql、02_seed.sql 和 03_ruoyi.sql。
-- 不包含 DROP/TRUNCATE，不自动覆盖已有表。导入遇错应立即停止。
-- 官方系统表来源：yangzongzhuan/RuoYi-Vue master/sql/ry_20260417.sql
-- 官方 Quartz 来源：同仓库 master/sql/quartz.sql；许可证见 vendor/LICENSE。
CREATE DATABASE ruoyi_traceability CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ruoyi_traceability;
SET NAMES utf8mb4;
SET time_zone = '+08:00';
`
writeFileSync(join(dir, 'ruoyi_traceability_full.sql'), [header, clean(read('vendor/ruoyi-system.sql')), clean(read('vendor/quartz.sql')), business, read('02_seed.sql'), read('03_ruoyi.sql')].join('\n\n'))
console.log('已生成完整导入文件和业务表文件。')
