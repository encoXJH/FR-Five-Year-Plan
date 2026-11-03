-- 一物一码追溯平台；MySQL >= 8.0.16；UTF-8
-- 仅用于新库初始化。已有若依库请只执行 01_business.sql、02_seed.sql 和 03_ruoyi.sql。
-- 不包含 DROP/TRUNCATE，不自动覆盖已有表。导入遇错应立即停止。
-- 官方系统表来源：yangzongzhuan/RuoYi-Vue master/sql/ry_20260417.sql
-- 官方 Quartz 来源：同仓库 master/sql/quartz.sql；许可证见 vendor/LICENSE。
CREATE DATABASE ruoyi_traceability CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ruoyi_traceability;
SET NAMES utf8mb4;
SET time_zone = '+08:00';


-- ----------------------------
-- 1、部门表
-- ----------------------------

create table sys_dept (
  dept_id           bigint(20)      not null auto_increment    comment '部门id',
  parent_id         bigint(20)      default 0                  comment '父部门id',
  ancestors         varchar(50)     default ''                 comment '祖级列表',
  dept_name         varchar(30)     default ''                 comment '部门名称',
  order_num         int(4)          default 0                  comment '显示顺序',
  leader            varchar(20)     default null               comment '负责人',
  phone             varchar(11)     default null               comment '联系电话',
  email             varchar(50)     default null               comment '邮箱',
  status            char(1)         default '0'                comment '部门状态（0正常 1停用）',
  del_flag          char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  primary key (dept_id)
) engine=innodb auto_increment=200 comment = '部门表';

-- ----------------------------
-- 初始化-部门表数据
-- ----------------------------
insert into sys_dept values(100,  0,   '0',          '若依科技',   0, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(101,  100, '0,100',      '深圳总公司', 1, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(102,  100, '0,100',      '长沙分公司', 2, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(103,  101, '0,100,101',  '研发部门',   1, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(104,  101, '0,100,101',  '市场部门',   2, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(105,  101, '0,100,101',  '测试部门',   3, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(106,  101, '0,100,101',  '财务部门',   4, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(107,  101, '0,100,101',  '运维部门',   5, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(108,  102, '0,100,102',  '市场部门',   1, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);
insert into sys_dept values(109,  102, '0,100,102',  '财务部门',   2, '若依', '15888888888', 'ry@qq.com', '0', '0', 'admin', sysdate(), '', null);


-- ----------------------------
-- 2、用户信息表
-- ----------------------------

create table sys_user (
  user_id           bigint(20)      not null auto_increment    comment '用户ID',
  dept_id           bigint(20)      default null               comment '部门ID',
  user_name         varchar(30)     not null                   comment '用户账号',
  nick_name         varchar(30)     not null                   comment '用户昵称',
  user_type         varchar(2)      default '00'               comment '用户类型（00系统用户）',
  email             varchar(50)     default ''                 comment '用户邮箱',
  phonenumber       varchar(11)     default ''                 comment '手机号码',
  sex               char(1)         default '0'                comment '用户性别（0男 1女 2未知）',
  avatar            varchar(100)    default ''                 comment '头像地址',
  password          varchar(100)    default ''                 comment '密码',
  status            char(1)         default '0'                comment '账号状态（0正常 1停用）',
  del_flag          char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  login_ip          varchar(128)    default ''                 comment '最后登录IP',
  login_date        datetime                                   comment '最后登录时间',
  pwd_update_date   datetime                                   comment '密码最后更新时间',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (user_id)
) engine=innodb auto_increment=100 comment = '用户信息表';

-- ----------------------------
-- 初始化-用户信息表数据
-- ----------------------------
insert into sys_user values(1,  103, 'admin', '若依', '00', 'ry@163.com', '15888888888', '1', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', '127.0.0.1', sysdate(), sysdate(), 'admin', sysdate(), '', null, '管理员');
insert into sys_user values(2,  105, 'ry',    '若依', '00', 'ry@qq.com',  '15666666666', '1', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', '127.0.0.1', sysdate(), sysdate(), 'admin', sysdate(), '', null, '测试员');


-- ----------------------------
-- 3、岗位信息表
-- ----------------------------

create table sys_post
(
  post_id       bigint(20)      not null auto_increment    comment '岗位ID',
  post_code     varchar(64)     not null                   comment '岗位编码',
  post_name     varchar(50)     not null                   comment '岗位名称',
  post_sort     int(4)          not null                   comment '显示顺序',
  status        char(1)         not null                   comment '状态（0正常 1停用）',
  create_by     varchar(64)     default ''                 comment '创建者',
  create_time   datetime                                   comment '创建时间',
  update_by     varchar(64)     default ''			       comment '更新者',
  update_time   datetime                                   comment '更新时间',
  remark        varchar(500)    default null               comment '备注',
  primary key (post_id)
) engine=innodb comment = '岗位信息表';

-- ----------------------------
-- 初始化-岗位信息表数据
-- ----------------------------
insert into sys_post values(1, 'ceo',  '董事长',    1, '0', 'admin', sysdate(), '', null, '');
insert into sys_post values(2, 'se',   '项目经理',  2, '0', 'admin', sysdate(), '', null, '');
insert into sys_post values(3, 'hr',   '人力资源',  3, '0', 'admin', sysdate(), '', null, '');
insert into sys_post values(4, 'user', '普通员工',  4, '0', 'admin', sysdate(), '', null, '');


-- ----------------------------
-- 4、角色信息表
-- ----------------------------

create table sys_role (
  role_id              bigint(20)      not null auto_increment    comment '角色ID',
  role_name            varchar(30)     not null                   comment '角色名称',
  role_key             varchar(100)    not null                   comment '角色权限字符串',
  role_sort            int(4)          not null                   comment '显示顺序',
  data_scope           char(1)         default '1'                comment '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
  menu_check_strictly  tinyint(1)      default 1                  comment '菜单树选择项是否关联显示',
  dept_check_strictly  tinyint(1)      default 1                  comment '部门树选择项是否关联显示',
  status               char(1)         not null                   comment '角色状态（0正常 1停用）',
  del_flag             char(1)         default '0'                comment '删除标志（0代表存在 2代表删除）',
  create_by            varchar(64)     default ''                 comment '创建者',
  create_time          datetime                                   comment '创建时间',
  update_by            varchar(64)     default ''                 comment '更新者',
  update_time          datetime                                   comment '更新时间',
  remark               varchar(500)    default null               comment '备注',
  primary key (role_id)
) engine=innodb auto_increment=100 comment = '角色信息表';

-- ----------------------------
-- 初始化-角色信息表数据
-- ----------------------------
insert into sys_role values('1', '超级管理员',  'admin',  1, 1, 1, 1, '0', '0', 'admin', sysdate(), '', null, '超级管理员');
insert into sys_role values('2', '普通角色',    'common', 2, 2, 1, 1, '0', '0', 'admin', sysdate(), '', null, '普通角色');


-- ----------------------------
-- 5、菜单权限表
-- ----------------------------

create table sys_menu (
  menu_id           bigint(20)      not null auto_increment    comment '菜单ID',
  menu_name         varchar(50)     not null                   comment '菜单名称',
  parent_id         bigint(20)      default 0                  comment '父菜单ID',
  order_num         int(4)          default 0                  comment '显示顺序',
  path              varchar(200)    default ''                 comment '路由地址',
  component         varchar(255)    default null               comment '组件路径',
  query             varchar(255)    default null               comment '路由参数',
  route_name        varchar(50)     default ''                 comment '路由名称',
  is_frame          int(1)          default 1                  comment '是否为外链（0是 1否）',
  is_cache          int(1)          default 0                  comment '是否缓存（0缓存 1不缓存）',
  menu_type         char(1)         default ''                 comment '菜单类型（M目录 C菜单 F按钮）',
  visible           char(1)         default 0                  comment '菜单状态（0显示 1隐藏）',
  status            char(1)         default 0                  comment '菜单状态（0正常 1停用）',
  perms             varchar(100)    default null               comment '权限标识',
  icon              varchar(100)    default '#'                comment '菜单图标',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default ''                 comment '备注',
  primary key (menu_id)
) engine=innodb auto_increment=2000 comment = '菜单权限表';

-- ----------------------------
-- 初始化-菜单信息表数据
-- ----------------------------
-- 一级菜单
insert into sys_menu values('1', '系统管理', '0', '1', 'system',           null, '', '', 1, 0, 'M', '0', '0', '', 'system',   'admin', sysdate(), '', null, '系统管理目录');
insert into sys_menu values('2', '系统监控', '0', '2', 'monitor',          null, '', '', 1, 0, 'M', '0', '0', '', 'monitor',  'admin', sysdate(), '', null, '系统监控目录');
insert into sys_menu values('3', '系统工具', '0', '3', 'tool',             null, '', '', 1, 0, 'M', '0', '0', '', 'tool',     'admin', sysdate(), '', null, '系统工具目录');
insert into sys_menu values('4', '若依官网', '0', '4', 'http://ruoyi.vip', null, '', '', 0, 0, 'M', '0', '0', '', 'guide',    'admin', sysdate(), '', null, '若依官网地址');
-- 二级菜单
insert into sys_menu values('100',  '用户管理', '1',   '1', 'user',       'system/user/index',        '', '', 1, 0, 'C', '0', '0', 'system:user:list',        'user',          'admin', sysdate(), '', null, '用户管理菜单');
insert into sys_menu values('101',  '角色管理', '1',   '2', 'role',       'system/role/index',        '', '', 1, 0, 'C', '0', '0', 'system:role:list',        'peoples',       'admin', sysdate(), '', null, '角色管理菜单');
insert into sys_menu values('102',  '菜单管理', '1',   '3', 'menu',       'system/menu/index',        '', '', 1, 0, 'C', '0', '0', 'system:menu:list',        'tree-table',    'admin', sysdate(), '', null, '菜单管理菜单');
insert into sys_menu values('103',  '部门管理', '1',   '4', 'dept',       'system/dept/index',        '', '', 1, 0, 'C', '0', '0', 'system:dept:list',        'tree',          'admin', sysdate(), '', null, '部门管理菜单');
insert into sys_menu values('104',  '岗位管理', '1',   '5', 'post',       'system/post/index',        '', '', 1, 0, 'C', '0', '0', 'system:post:list',        'post',          'admin', sysdate(), '', null, '岗位管理菜单');
insert into sys_menu values('105',  '字典管理', '1',   '6', 'dict',       'system/dict/index',        '', '', 1, 0, 'C', '0', '0', 'system:dict:list',        'dict',          'admin', sysdate(), '', null, '字典管理菜单');
insert into sys_menu values('106',  '参数设置', '1',   '7', 'config',     'system/config/index',      '', '', 1, 0, 'C', '0', '0', 'system:config:list',      'edit',          'admin', sysdate(), '', null, '参数设置菜单');
insert into sys_menu values('107',  '通知公告', '1',   '8', 'notice',     'system/notice/index',      '', '', 1, 0, 'C', '0', '0', 'system:notice:list',      'message',       'admin', sysdate(), '', null, '通知公告菜单');
insert into sys_menu values('108',  '日志管理', '1',   '9', 'log',        '',                         '', '', 1, 0, 'M', '0', '0', '',                        'log',           'admin', sysdate(), '', null, '日志管理菜单');
insert into sys_menu values('109',  '在线用户', '2',   '1', 'online',     'monitor/online/index',     '', '', 1, 0, 'C', '0', '0', 'monitor:online:list',     'online',        'admin', sysdate(), '', null, '在线用户菜单');
insert into sys_menu values('110',  '定时任务', '2',   '2', 'job',        'monitor/job/index',        '', '', 1, 0, 'C', '0', '0', 'monitor:job:list',        'job',           'admin', sysdate(), '', null, '定时任务菜单');
insert into sys_menu values('111',  '数据监控', '2',   '3', 'druid',      'monitor/druid/index',      '', '', 1, 0, 'C', '0', '0', 'monitor:druid:list',      'druid',         'admin', sysdate(), '', null, '数据监控菜单');
insert into sys_menu values('112',  '服务监控', '2',   '4', 'server',     'monitor/server/index',     '', '', 1, 0, 'C', '0', '0', 'monitor:server:list',     'server',        'admin', sysdate(), '', null, '服务监控菜单');
insert into sys_menu values('113',  '缓存监控', '2',   '5', 'cache',      'monitor/cache/index',      '', '', 1, 0, 'C', '0', '0', 'monitor:cache:list',      'redis',         'admin', sysdate(), '', null, '缓存监控菜单');
insert into sys_menu values('114',  '缓存列表', '2',   '6', 'cacheList',  'monitor/cache/list',       '', '', 1, 0, 'C', '0', '0', 'monitor:cache:list',      'redis-list',    'admin', sysdate(), '', null, '缓存列表菜单');
insert into sys_menu values('115',  '表单构建', '3',   '1', 'build',      'tool/build/index',         '', '', 1, 0, 'C', '0', '0', 'tool:build:list',         'build',         'admin', sysdate(), '', null, '表单构建菜单');
insert into sys_menu values('116',  '代码生成', '3',   '2', 'gen',        'tool/gen/index',           '', '', 1, 0, 'C', '0', '0', 'tool:gen:list',           'code',          'admin', sysdate(), '', null, '代码生成菜单');
insert into sys_menu values('117',  '系统接口', '3',   '3', 'swagger',    'tool/swagger/index',       '', '', 1, 0, 'C', '0', '0', 'tool:swagger:list',       'swagger',       'admin', sysdate(), '', null, '系统接口菜单');
-- 三级菜单
insert into sys_menu values('500',  '操作日志', '108', '1', 'operlog',    'monitor/operlog/index',    '', '', 1, 0, 'C', '0', '0', 'monitor:operlog:list',    'form',          'admin', sysdate(), '', null, '操作日志菜单');
insert into sys_menu values('501',  '登录日志', '108', '2', 'logininfor', 'monitor/logininfor/index', '', '', 1, 0, 'C', '0', '0', 'monitor:logininfor:list', 'logininfor',    'admin', sysdate(), '', null, '登录日志菜单');
-- 用户管理按钮
insert into sys_menu values('1000', '用户查询', '100', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1001', '用户新增', '100', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1002', '用户修改', '100', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1003', '用户删除', '100', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1004', '用户导出', '100', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:export',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1005', '用户导入', '100', '6',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:import',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1006', '重置密码', '100', '7',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:user:resetPwd',       '#', 'admin', sysdate(), '', null, '');
-- 角色管理按钮
insert into sys_menu values('1007', '角色查询', '101', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1008', '角色新增', '101', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1009', '角色修改', '101', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1010', '角色删除', '101', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1011', '角色导出', '101', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:role:export',         '#', 'admin', sysdate(), '', null, '');
-- 菜单管理按钮
insert into sys_menu values('1012', '菜单查询', '102', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1013', '菜单新增', '102', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1014', '菜单修改', '102', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1015', '菜单删除', '102', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:menu:remove',         '#', 'admin', sysdate(), '', null, '');
-- 部门管理按钮
insert into sys_menu values('1016', '部门查询', '103', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1017', '部门新增', '103', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1018', '部门修改', '103', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1019', '部门删除', '103', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:dept:remove',         '#', 'admin', sysdate(), '', null, '');
-- 岗位管理按钮
insert into sys_menu values('1020', '岗位查询', '104', '1',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1021', '岗位新增', '104', '2',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1022', '岗位修改', '104', '3',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1023', '岗位删除', '104', '4',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1024', '岗位导出', '104', '5',  '', '', '', '', 1, 0, 'F', '0', '0', 'system:post:export',         '#', 'admin', sysdate(), '', null, '');
-- 字典管理按钮
insert into sys_menu values('1025', '字典查询', '105', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1026', '字典新增', '105', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1027', '字典修改', '105', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1028', '字典删除', '105', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1029', '字典导出', '105', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:dict:export',         '#', 'admin', sysdate(), '', null, '');
-- 参数设置按钮
insert into sys_menu values('1030', '参数查询', '106', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:query',        '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1031', '参数新增', '106', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:add',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1032', '参数修改', '106', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:edit',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1033', '参数删除', '106', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:remove',       '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1034', '参数导出', '106', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:config:export',       '#', 'admin', sysdate(), '', null, '');
-- 通知公告按钮
insert into sys_menu values('1035', '公告查询', '107', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:notice:query',        '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1036', '公告新增', '107', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:notice:add',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1037', '公告修改', '107', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:notice:edit',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1038', '公告删除', '107', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'system:notice:remove',       '#', 'admin', sysdate(), '', null, '');
-- 操作日志按钮
insert into sys_menu values('1039', '操作查询', '500', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:query',      '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1040', '操作删除', '500', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:remove',     '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1041', '日志导出', '500', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:export',     '#', 'admin', sysdate(), '', null, '');
-- 登录日志按钮
insert into sys_menu values('1042', '登录查询', '501', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:query',   '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1043', '登录删除', '501', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:remove',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1044', '日志导出', '501', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:export',  '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1045', '账户解锁', '501', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:unlock',  '#', 'admin', sysdate(), '', null, '');
-- 在线用户按钮
insert into sys_menu values('1046', '在线查询', '109', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:online:query',       '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1047', '批量强退', '109', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:online:batchLogout', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1048', '单条强退', '109', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:online:forceLogout', '#', 'admin', sysdate(), '', null, '');
-- 定时任务按钮
insert into sys_menu values('1049', '任务查询', '110', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:query',          '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1050', '任务新增', '110', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:add',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1051', '任务修改', '110', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:edit',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1052', '任务删除', '110', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:remove',         '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1053', '状态修改', '110', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:changeStatus',   '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1054', '任务导出', '110', '6', '#', '', '', '', 1, 0, 'F', '0', '0', 'monitor:job:export',         '#', 'admin', sysdate(), '', null, '');
-- 代码生成按钮
insert into sys_menu values('1055', '生成查询', '116', '1', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:query',             '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1056', '生成修改', '116', '2', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:edit',              '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1057', '生成删除', '116', '3', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:remove',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1058', '导入代码', '116', '4', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:import',            '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1059', '预览代码', '116', '5', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:preview',           '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('1060', '生成代码', '116', '6', '#', '', '', '', 1, 0, 'F', '0', '0', 'tool:gen:code',              '#', 'admin', sysdate(), '', null, '');


-- ----------------------------
-- 6、用户和角色关联表  用户N-1角色
-- ----------------------------

create table sys_user_role (
  user_id   bigint(20) not null comment '用户ID',
  role_id   bigint(20) not null comment '角色ID',
  primary key(user_id, role_id)
) engine=innodb comment = '用户和角色关联表';

-- ----------------------------
-- 初始化-用户和角色关联表数据
-- ----------------------------
insert into sys_user_role values ('1', '1');
insert into sys_user_role values ('2', '2');


-- ----------------------------
-- 7、角色和菜单关联表  角色1-N菜单
-- ----------------------------

create table sys_role_menu (
  role_id   bigint(20) not null comment '角色ID',
  menu_id   bigint(20) not null comment '菜单ID',
  primary key(role_id, menu_id)
) engine=innodb comment = '角色和菜单关联表';

-- ----------------------------
-- 初始化-角色和菜单关联表数据
-- ----------------------------
insert into sys_role_menu values ('2', '1');
insert into sys_role_menu values ('2', '2');
insert into sys_role_menu values ('2', '3');
insert into sys_role_menu values ('2', '4');
insert into sys_role_menu values ('2', '100');
insert into sys_role_menu values ('2', '101');
insert into sys_role_menu values ('2', '102');
insert into sys_role_menu values ('2', '103');
insert into sys_role_menu values ('2', '104');
insert into sys_role_menu values ('2', '105');
insert into sys_role_menu values ('2', '106');
insert into sys_role_menu values ('2', '107');
insert into sys_role_menu values ('2', '108');
insert into sys_role_menu values ('2', '109');
insert into sys_role_menu values ('2', '110');
insert into sys_role_menu values ('2', '111');
insert into sys_role_menu values ('2', '112');
insert into sys_role_menu values ('2', '113');
insert into sys_role_menu values ('2', '114');
insert into sys_role_menu values ('2', '115');
insert into sys_role_menu values ('2', '116');
insert into sys_role_menu values ('2', '117');
insert into sys_role_menu values ('2', '500');
insert into sys_role_menu values ('2', '501');
insert into sys_role_menu values ('2', '1000');
insert into sys_role_menu values ('2', '1001');
insert into sys_role_menu values ('2', '1002');
insert into sys_role_menu values ('2', '1003');
insert into sys_role_menu values ('2', '1004');
insert into sys_role_menu values ('2', '1005');
insert into sys_role_menu values ('2', '1006');
insert into sys_role_menu values ('2', '1007');
insert into sys_role_menu values ('2', '1008');
insert into sys_role_menu values ('2', '1009');
insert into sys_role_menu values ('2', '1010');
insert into sys_role_menu values ('2', '1011');
insert into sys_role_menu values ('2', '1012');
insert into sys_role_menu values ('2', '1013');
insert into sys_role_menu values ('2', '1014');
insert into sys_role_menu values ('2', '1015');
insert into sys_role_menu values ('2', '1016');
insert into sys_role_menu values ('2', '1017');
insert into sys_role_menu values ('2', '1018');
insert into sys_role_menu values ('2', '1019');
insert into sys_role_menu values ('2', '1020');
insert into sys_role_menu values ('2', '1021');
insert into sys_role_menu values ('2', '1022');
insert into sys_role_menu values ('2', '1023');
insert into sys_role_menu values ('2', '1024');
insert into sys_role_menu values ('2', '1025');
insert into sys_role_menu values ('2', '1026');
insert into sys_role_menu values ('2', '1027');
insert into sys_role_menu values ('2', '1028');
insert into sys_role_menu values ('2', '1029');
insert into sys_role_menu values ('2', '1030');
insert into sys_role_menu values ('2', '1031');
insert into sys_role_menu values ('2', '1032');
insert into sys_role_menu values ('2', '1033');
insert into sys_role_menu values ('2', '1034');
insert into sys_role_menu values ('2', '1035');
insert into sys_role_menu values ('2', '1036');
insert into sys_role_menu values ('2', '1037');
insert into sys_role_menu values ('2', '1038');
insert into sys_role_menu values ('2', '1039');
insert into sys_role_menu values ('2', '1040');
insert into sys_role_menu values ('2', '1041');
insert into sys_role_menu values ('2', '1042');
insert into sys_role_menu values ('2', '1043');
insert into sys_role_menu values ('2', '1044');
insert into sys_role_menu values ('2', '1045');
insert into sys_role_menu values ('2', '1046');
insert into sys_role_menu values ('2', '1047');
insert into sys_role_menu values ('2', '1048');
insert into sys_role_menu values ('2', '1049');
insert into sys_role_menu values ('2', '1050');
insert into sys_role_menu values ('2', '1051');
insert into sys_role_menu values ('2', '1052');
insert into sys_role_menu values ('2', '1053');
insert into sys_role_menu values ('2', '1054');
insert into sys_role_menu values ('2', '1055');
insert into sys_role_menu values ('2', '1056');
insert into sys_role_menu values ('2', '1057');
insert into sys_role_menu values ('2', '1058');
insert into sys_role_menu values ('2', '1059');
insert into sys_role_menu values ('2', '1060');

-- ----------------------------
-- 8、角色和部门关联表  角色1-N部门
-- ----------------------------

create table sys_role_dept (
  role_id   bigint(20) not null comment '角色ID',
  dept_id   bigint(20) not null comment '部门ID',
  primary key(role_id, dept_id)
) engine=innodb comment = '角色和部门关联表';

-- ----------------------------
-- 初始化-角色和部门关联表数据
-- ----------------------------
insert into sys_role_dept values ('2', '100');
insert into sys_role_dept values ('2', '101');
insert into sys_role_dept values ('2', '105');


-- ----------------------------
-- 9、用户与岗位关联表  用户1-N岗位
-- ----------------------------

create table sys_user_post
(
  user_id   bigint(20) not null comment '用户ID',
  post_id   bigint(20) not null comment '岗位ID',
  primary key (user_id, post_id)
) engine=innodb comment = '用户与岗位关联表';

-- ----------------------------
-- 初始化-用户与岗位关联表数据
-- ----------------------------
insert into sys_user_post values ('1', '1');
insert into sys_user_post values ('2', '2');


-- ----------------------------
-- 10、操作日志记录
-- ----------------------------

create table sys_oper_log (
  oper_id           bigint(20)      not null auto_increment    comment '日志主键',
  title             varchar(50)     default ''                 comment '模块标题',
  business_type     int(2)          default 0                  comment '业务类型（0其它 1新增 2修改 3删除）',
  method            varchar(200)    default ''                 comment '方法名称',
  request_method    varchar(10)     default ''                 comment '请求方式',
  operator_type     int(1)          default 0                  comment '操作类别（0其它 1后台用户 2手机端用户）',
  oper_name         varchar(50)     default ''                 comment '操作人员',
  dept_name         varchar(50)     default ''                 comment '部门名称',
  oper_url          varchar(255)    default ''                 comment '请求URL',
  oper_ip           varchar(128)    default ''                 comment '主机地址',
  oper_location     varchar(255)    default ''                 comment '操作地点',
  oper_param        varchar(2000)   default ''                 comment '请求参数',
  json_result       varchar(2000)   default ''                 comment '返回参数',
  status            int(1)          default 0                  comment '操作状态（0正常 1异常）',
  error_msg         varchar(2000)   default ''                 comment '错误消息',
  oper_time         datetime                                   comment '操作时间',
  cost_time         bigint(20)      default 0                  comment '消耗时间',
  primary key (oper_id),
  key idx_sys_oper_log_bt (business_type),
  key idx_sys_oper_log_s  (status),
  key idx_sys_oper_log_ot (oper_time)
) engine=innodb auto_increment=100 comment = '操作日志记录';


-- ----------------------------
-- 11、字典类型表
-- ----------------------------

create table sys_dict_type
(
  dict_id          bigint(20)      not null auto_increment    comment '字典主键',
  dict_name        varchar(100)    default ''                 comment '字典名称',
  dict_type        varchar(100)    default ''                 comment '字典类型',
  status           char(1)         default '0'                comment '状态（0正常 1停用）',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default null               comment '备注',
  primary key (dict_id),
  unique (dict_type)
) engine=innodb auto_increment=100 comment = '字典类型表';

insert into sys_dict_type values(1,  '用户性别', 'sys_user_sex',        '0', 'admin', sysdate(), '', null, '用户性别列表');
insert into sys_dict_type values(2,  '菜单状态', 'sys_show_hide',       '0', 'admin', sysdate(), '', null, '菜单状态列表');
insert into sys_dict_type values(3,  '系统开关', 'sys_normal_disable',  '0', 'admin', sysdate(), '', null, '系统开关列表');
insert into sys_dict_type values(4,  '任务状态', 'sys_job_status',      '0', 'admin', sysdate(), '', null, '任务状态列表');
insert into sys_dict_type values(5,  '任务分组', 'sys_job_group',       '0', 'admin', sysdate(), '', null, '任务分组列表');
insert into sys_dict_type values(6,  '系统是否', 'sys_yes_no',          '0', 'admin', sysdate(), '', null, '系统是否列表');
insert into sys_dict_type values(7,  '通知类型', 'sys_notice_type',     '0', 'admin', sysdate(), '', null, '通知类型列表');
insert into sys_dict_type values(8,  '通知状态', 'sys_notice_status',   '0', 'admin', sysdate(), '', null, '通知状态列表');
insert into sys_dict_type values(9,  '操作类型', 'sys_oper_type',       '0', 'admin', sysdate(), '', null, '操作类型列表');
insert into sys_dict_type values(10, '系统状态', 'sys_common_status',   '0', 'admin', sysdate(), '', null, '登录状态列表');


-- ----------------------------
-- 12、字典数据表
-- ----------------------------

create table sys_dict_data
(
  dict_code        bigint(20)      not null auto_increment    comment '字典编码',
  dict_sort        int(4)          default 0                  comment '字典排序',
  dict_label       varchar(100)    default ''                 comment '字典标签',
  dict_value       varchar(100)    default ''                 comment '字典键值',
  dict_type        varchar(100)    default ''                 comment '字典类型',
  css_class        varchar(100)    default null               comment '样式属性（其他样式扩展）',
  list_class       varchar(100)    default null               comment '表格回显样式',
  is_default       char(1)         default 'N'                comment '是否默认（Y是 N否）',
  status           char(1)         default '0'                comment '状态（0正常 1停用）',
  create_by        varchar(64)     default ''                 comment '创建者',
  create_time      datetime                                   comment '创建时间',
  update_by        varchar(64)     default ''                 comment '更新者',
  update_time      datetime                                   comment '更新时间',
  remark           varchar(500)    default null               comment '备注',
  primary key (dict_code)
) engine=innodb auto_increment=100 comment = '字典数据表';

insert into sys_dict_data values(1,  1,  '男',       '0',       'sys_user_sex',        '',   '',        'Y', '0', 'admin', sysdate(), '', null, '性别男');
insert into sys_dict_data values(2,  2,  '女',       '1',       'sys_user_sex',        '',   '',        'N', '0', 'admin', sysdate(), '', null, '性别女');
insert into sys_dict_data values(3,  3,  '未知',     '2',       'sys_user_sex',        '',   '',        'N', '0', 'admin', sysdate(), '', null, '性别未知');
insert into sys_dict_data values(4,  1,  '显示',     '0',       'sys_show_hide',       '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '显示菜单');
insert into sys_dict_data values(5,  2,  '隐藏',     '1',       'sys_show_hide',       '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '隐藏菜单');
insert into sys_dict_data values(6,  1,  '正常',     '0',       'sys_normal_disable',  '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '正常状态');
insert into sys_dict_data values(7,  2,  '停用',     '1',       'sys_normal_disable',  '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '停用状态');
insert into sys_dict_data values(8,  1,  '正常',     '0',       'sys_job_status',      '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '正常状态');
insert into sys_dict_data values(9,  2,  '暂停',     '1',       'sys_job_status',      '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '停用状态');
insert into sys_dict_data values(10, 1,  '默认',     'DEFAULT', 'sys_job_group',       '',   '',        'Y', '0', 'admin', sysdate(), '', null, '默认分组');
insert into sys_dict_data values(11, 2,  '系统',     'SYSTEM',  'sys_job_group',       '',   '',        'N', '0', 'admin', sysdate(), '', null, '系统分组');
insert into sys_dict_data values(12, 1,  '是',       'Y',       'sys_yes_no',          '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '系统默认是');
insert into sys_dict_data values(13, 2,  '否',       'N',       'sys_yes_no',          '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '系统默认否');
insert into sys_dict_data values(14, 1,  '通知',     '1',       'sys_notice_type',     '',   'warning', 'Y', '0', 'admin', sysdate(), '', null, '通知');
insert into sys_dict_data values(15, 2,  '公告',     '2',       'sys_notice_type',     '',   'success', 'N', '0', 'admin', sysdate(), '', null, '公告');
insert into sys_dict_data values(16, 1,  '正常',     '0',       'sys_notice_status',   '',   'primary', 'Y', '0', 'admin', sysdate(), '', null, '正常状态');
insert into sys_dict_data values(17, 2,  '关闭',     '1',       'sys_notice_status',   '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '关闭状态');
insert into sys_dict_data values(18, 99, '其他',     '0',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', sysdate(), '', null, '其他操作');
insert into sys_dict_data values(19, 1,  '新增',     '1',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', sysdate(), '', null, '新增操作');
insert into sys_dict_data values(20, 2,  '修改',     '2',       'sys_oper_type',       '',   'info',    'N', '0', 'admin', sysdate(), '', null, '修改操作');
insert into sys_dict_data values(21, 3,  '删除',     '3',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '删除操作');
insert into sys_dict_data values(22, 4,  '授权',     '4',       'sys_oper_type',       '',   'primary', 'N', '0', 'admin', sysdate(), '', null, '授权操作');
insert into sys_dict_data values(23, 5,  '导出',     '5',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', sysdate(), '', null, '导出操作');
insert into sys_dict_data values(24, 6,  '导入',     '6',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', sysdate(), '', null, '导入操作');
insert into sys_dict_data values(25, 7,  '强退',     '7',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '强退操作');
insert into sys_dict_data values(26, 8,  '生成代码', '8',       'sys_oper_type',       '',   'warning', 'N', '0', 'admin', sysdate(), '', null, '生成操作');
insert into sys_dict_data values(27, 9,  '清空数据', '9',       'sys_oper_type',       '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '清空操作');
insert into sys_dict_data values(28, 1,  '成功',     '0',       'sys_common_status',   '',   'primary', 'N', '0', 'admin', sysdate(), '', null, '正常状态');
insert into sys_dict_data values(29, 2,  '失败',     '1',       'sys_common_status',   '',   'danger',  'N', '0', 'admin', sysdate(), '', null, '停用状态');


-- ----------------------------
-- 13、参数配置表
-- ----------------------------

create table sys_config (
  config_id         int(5)          not null auto_increment    comment '参数主键',
  config_name       varchar(100)    default ''                 comment '参数名称',
  config_key        varchar(100)    default ''                 comment '参数键名',
  config_value      varchar(500)    default ''                 comment '参数键值',
  config_type       char(1)         default 'N'                comment '系统内置（Y是 N否）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (config_id)
) engine=innodb auto_increment=100 comment = '参数配置表';

insert into sys_config values(1, '主框架页-默认皮肤样式名称',     'sys.index.skinName',               'skin-blue',     'Y', 'admin', sysdate(), '', null, '蓝色 skin-blue、绿色 skin-green、紫色 skin-purple、红色 skin-red、黄色 skin-yellow' );
insert into sys_config values(2, '用户管理-账号初始密码',         'sys.user.initPassword',            '123456',        'Y', 'admin', sysdate(), '', null, '初始化密码 123456' );
insert into sys_config values(3, '主框架页-侧边栏主题',           'sys.index.sideTheme',              'theme-dark',    'Y', 'admin', sysdate(), '', null, '深色主题theme-dark，浅色主题theme-light' );
insert into sys_config values(4, '账号自助-验证码开关',           'sys.account.captchaEnabled',       'true',          'Y', 'admin', sysdate(), '', null, '是否开启验证码功能（true开启，false关闭）');
insert into sys_config values(5, '账号自助-是否开启用户注册功能', 'sys.account.registerUser',         'false',         'Y', 'admin', sysdate(), '', null, '是否开启注册用户功能（true开启，false关闭）');
insert into sys_config values(6, '用户登录-黑名单列表',           'sys.login.blackIPList',            '',              'Y', 'admin', sysdate(), '', null, '设置登录IP黑名单限制，多个匹配项以;分隔，支持匹配（*通配、网段）');
insert into sys_config values(7, '用户管理-初始密码修改策略',     'sys.account.initPasswordModify',   '1',             'Y', 'admin', sysdate(), '', null, '0：初始密码修改策略关闭，没有任何提示，1：提醒用户，如果未修改初始密码，则在登录时就会提醒修改密码对话框');
insert into sys_config values(8, '用户管理-账号密码更新周期',     'sys.account.passwordValidateDays', '0',             'Y', 'admin', sysdate(), '', null, '密码更新周期（填写数字，数据初始化值为0不限制，若修改必须为大于0小于365的正整数），如果超过这个周期登录系统时，则在登录时就会提醒修改密码对话框');
insert into sys_config values(9, '用户管理-密码字符范围',         'sys.account.chrtype',              '0',             'Y', 'admin', sysdate(), '', null, '默认任意字符范围，0任意（密码可以输入任意字符），1数字（密码只能为0-9数字），2英文字母（密码只能为a-z和A-Z字母），3字母和数字（密码必须包含字母，数字）,4字母数字和特殊字符（目前支持的特殊字符包括：~!@#$%^&*()-=_+）');


-- ----------------------------
-- 14、系统访问记录
-- ----------------------------

create table sys_logininfor (
  info_id        bigint(20)     not null auto_increment   comment '访问ID',
  user_name      varchar(50)    default ''                comment '用户账号',
  ipaddr         varchar(128)   default ''                comment '登录IP地址',
  login_location varchar(255)   default ''                comment '登录地点',
  browser        varchar(50)    default ''                comment '浏览器类型',
  os             varchar(50)    default ''                comment '操作系统',
  status         char(1)        default '0'               comment '登录状态（0成功 1失败）',
  msg            varchar(255)   default ''                comment '提示消息',
  login_time     datetime                                 comment '访问时间',
  primary key (info_id),
  key idx_sys_logininfor_s  (status),
  key idx_sys_logininfor_lt (login_time)
) engine=innodb auto_increment=100 comment = '系统访问记录';


-- ----------------------------
-- 15、定时任务调度表
-- ----------------------------

create table sys_job (
  job_id              bigint(20)    not null auto_increment    comment '任务ID',
  job_name            varchar(64)   default ''                 comment '任务名称',
  job_group           varchar(64)   default 'DEFAULT'          comment '任务组名',
  invoke_target       varchar(500)  not null                   comment '调用目标字符串',
  cron_expression     varchar(255)  default ''                 comment 'cron执行表达式',
  misfire_policy      varchar(20)   default '3'                comment '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
  concurrent          char(1)       default '1'                comment '是否并发执行（0允许 1禁止）',
  status              char(1)       default '0'                comment '状态（0正常 1暂停）',
  create_by           varchar(64)   default ''                 comment '创建者',
  create_time         datetime                                 comment '创建时间',
  update_by           varchar(64)   default ''                 comment '更新者',
  update_time         datetime                                 comment '更新时间',
  remark              varchar(500)  default ''                 comment '备注信息',
  primary key (job_id, job_name, job_group)
) engine=innodb auto_increment=100 comment = '定时任务调度表';

insert into sys_job values(1, '系统默认（无参）', 'DEFAULT', 'ryTask.ryNoParams',        '0/10 * * * * ?', '3', '1', '1', 'admin', sysdate(), '', null, '');
insert into sys_job values(2, '系统默认（有参）', 'DEFAULT', 'ryTask.ryParams(\'ry\')',  '0/15 * * * * ?', '3', '1', '1', 'admin', sysdate(), '', null, '');
insert into sys_job values(3, '系统默认（多参）', 'DEFAULT', 'ryTask.ryMultipleParams(\'ry\', true, 2000L, 316.50D, 100)',  '0/20 * * * * ?', '3', '1', '1', 'admin', sysdate(), '', null, '');


-- ----------------------------
-- 16、定时任务调度日志表
-- ----------------------------

create table sys_job_log (
  job_log_id          bigint(20)     not null auto_increment    comment '任务日志ID',
  job_name            varchar(64)    not null                   comment '任务名称',
  job_group           varchar(64)    not null                   comment '任务组名',
  invoke_target       varchar(500)   not null                   comment '调用目标字符串',
  job_message         varchar(500)                              comment '日志信息',
  status              char(1)        default '0'                comment '执行状态（0正常 1失败）',
  exception_info      varchar(2000)  default ''                 comment '异常信息',
  start_time          datetime                                  comment '执行开始时间',
  end_time            datetime                                  comment '执行结束时间',
  create_time         datetime                                  comment '创建时间',
  primary key (job_log_id)
) engine=innodb comment = '定时任务调度日志表';


-- ----------------------------
-- 17、通知公告表
-- ----------------------------

create table sys_notice (
  notice_id         int(4)          not null auto_increment    comment '公告ID',
  notice_title      varchar(50)     not null                   comment '公告标题',
  notice_type       char(1)         not null                   comment '公告类型（1通知 2公告）',
  notice_content    longblob        default null               comment '公告内容',
  status            char(1)         default '0'                comment '公告状态（0正常 1关闭）',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time       datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(255)    default null               comment '备注',
  primary key (notice_id)
) engine=innodb auto_increment=10 comment = '通知公告表';

-- ----------------------------
-- 初始化-公告信息表数据
-- ----------------------------
insert into sys_notice values('1', '温馨提醒：2018-07-01 若依新版本发布啦', '2', '新版本内容', '0', 'admin', sysdate(), '', null, '管理员');
insert into sys_notice values('2', '维护通知：2018-07-01 若依系统凌晨维护', '1', '维护内容',   '0', 'admin', sysdate(), '', null, '管理员');
insert into sys_notice values('3', '若依开源框架介绍', '1', '<p><span style=\"color: rgb(230, 0, 0);\">项目介绍</span></p><p><font color=\"#333333\">RuoYi开源项目是为企业用户定制的后台脚手架框架，为企业打造的一站式解决方案，降低企业开发成本，提升开发效率。主要包括用户管理、角色管理、部门管理、菜单管理、参数管理、字典管理、</font><span style=\"color: rgb(51, 51, 51);\">岗位管理</span><span style=\"color: rgb(51, 51, 51);\">、定时任务</span><span style=\"color: rgb(51, 51, 51);\">、</span><span style=\"color: rgb(51, 51, 51);\">服务监控、登录日志、操作日志、代码生成等功能。其中，还支持多数据源、数据权限、国际化、Redis缓存、Docker部署、滑动验证码、第三方认证登录、分布式事务、</span><font color=\"#333333\">分布式文件存储</font><span style=\"color: rgb(51, 51, 51);\">、分库分表处理等技术特点。</span></p><p><img src=\"https://foruda.gitee.com/images/1773931848342439032/a4d22313_1815095.png\" style=\"width: 64px;\"><br></p><p><span style=\"color: rgb(230, 0, 0);\">官网及演示</span></p><p><span style=\"color: rgb(51, 51, 51);\">若依官网地址：&nbsp;</span><a href=\"http://ruoyi.vip\" target=\"_blank\">http://ruoyi.vip</a><a href=\"http://ruoyi.vip\" target=\"_blank\"></a></p><p><span style=\"color: rgb(51, 51, 51);\">若依文档地址：&nbsp;</span><a href=\"http://doc.ruoyi.vip\" target=\"_blank\">http://doc.ruoyi.vip</a><br></p><p><span style=\"color: rgb(51, 51, 51);\">演示地址【不分离版】：&nbsp;</span><a href=\"http://demo.ruoyi.vip\" target=\"_blank\">http://demo.ruoyi.vip</a></p><p><span style=\"color: rgb(51, 51, 51);\">演示地址【分离版本】：&nbsp;</span><a href=\"http://vue.ruoyi.vip\" target=\"_blank\">http://vue.ruoyi.vip</a></p><p><span style=\"color: rgb(51, 51, 51);\">演示地址【微服务版】：&nbsp;</span><a href=\"http://cloud.ruoyi.vip\" target=\"_blank\">http://cloud.ruoyi.vip</a></p><p><span style=\"color: rgb(51, 51, 51);\">演示地址【移动端版】：&nbsp;</span><a href=\"http://h5.ruoyi.vip\" target=\"_blank\">http://h5.ruoyi.vip</a></p><p><br style=\"color: rgb(48, 49, 51); font-family: &quot;Helvetica Neue&quot;, Helvetica, Arial, sans-serif; font-size: 12px;\"></p>', '0', 'admin', sysdate(), '', null, '管理员');


-- ----------------------------
-- 18、公告已读记录表
-- ----------------------------

create table sys_notice_read (
  read_id          bigint(20)       not null auto_increment    comment '已读主键',
  notice_id        int(4)           not null                   comment '公告id',
  user_id          bigint(20)       not null                   comment '用户id',
  read_time        datetime         not null                   comment '阅读时间',
  primary key (read_id),
  unique key uk_user_notice (user_id, notice_id)   comment '同一用户同一公告只记录一次'
) engine=innodb auto_increment=1 comment='公告已读记录表';


-- ----------------------------
-- 19、代码生成业务表
-- ----------------------------

create table gen_table (
  table_id          bigint(20)      not null auto_increment    comment '编号',
  table_name        varchar(200)    default ''                 comment '表名称',
  table_comment     varchar(500)    default ''                 comment '表描述',
  sub_table_name    varchar(64)     default null               comment '关联子表的表名',
  sub_table_fk_name varchar(64)     default null               comment '子表关联的外键名',
  class_name        varchar(100)    default ''                 comment '实体类名称',
  tpl_category      varchar(200)    default 'crud'             comment '使用的模板（crud单表操作 tree树表操作）',
  tpl_web_type      varchar(30)     default ''                 comment '前端模板类型（element-ui模版 element-plus模版）',
  package_name      varchar(100)                               comment '生成包路径',
  module_name       varchar(30)                                comment '生成模块名',
  business_name     varchar(30)                                comment '生成业务名',
  function_name     varchar(50)                                comment '生成功能名',
  function_author   varchar(50)                                comment '生成功能作者',
  form_col_num      int(1)          default 1                  comment '表单布局（单列 双列 三列）',
  gen_type          char(1)         default '0'                comment '生成代码方式（0zip压缩包 1自定义路径）',
  gen_path          varchar(200)    default '/'                comment '生成路径（不填默认项目路径）',
  options           varchar(1000)                              comment '其它生成选项',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  remark            varchar(500)    default null               comment '备注',
  primary key (table_id)
) engine=innodb auto_increment=1 comment = '代码生成业务表';


-- ----------------------------
-- 20、代码生成业务表字段
-- ----------------------------

create table gen_table_column (
  column_id         bigint(20)      not null auto_increment    comment '编号',
  table_id          bigint(20)                                 comment '归属表编号',
  column_name       varchar(200)                               comment '列名称',
  column_comment    varchar(500)                               comment '列描述',
  column_type       varchar(100)                               comment '列类型',
  java_type         varchar(500)                               comment 'JAVA类型',
  java_field        varchar(200)                               comment 'JAVA字段名',
  is_pk             char(1)                                    comment '是否主键（1是）',
  is_increment      char(1)                                    comment '是否自增（1是）',
  is_required       char(1)                                    comment '是否必填（1是）',
  is_insert         char(1)                                    comment '是否为插入字段（1是）',
  is_edit           char(1)                                    comment '是否编辑字段（1是）',
  is_list           char(1)                                    comment '是否列表字段（1是）',
  is_query          char(1)                                    comment '是否查询字段（1是）',
  query_type        varchar(200)    default 'EQ'               comment '查询方式（等于、不等于、大于、小于、范围）',
  html_type         varchar(200)                               comment '显示类型（文本框、文本域、下拉框、复选框、单选框、日期控件）',
  dict_type         varchar(200)    default ''                 comment '字典类型',
  sort              int                                        comment '排序',
  create_by         varchar(64)     default ''                 comment '创建者',
  create_time 	    datetime                                   comment '创建时间',
  update_by         varchar(64)     default ''                 comment '更新者',
  update_time       datetime                                   comment '更新时间',
  primary key (column_id)
) engine=innodb auto_increment=1 comment = '代码生成业务表字段';


-- ----------------------------
-- 1、存储每一个已配置的 jobDetail 的详细信息
-- ----------------------------
create table QRTZ_JOB_DETAILS (
    sched_name           varchar(120)    not null            comment '调度名称',
    job_name             varchar(200)    not null            comment '任务名称',
    job_group            varchar(200)    not null            comment '任务组名',
    description          varchar(250)    null                comment '相关介绍',
    job_class_name       varchar(250)    not null            comment '执行任务类名称',
    is_durable           varchar(1)      not null            comment '是否持久化',
    is_nonconcurrent     varchar(1)      not null            comment '是否并发',
    is_update_data       varchar(1)      not null            comment '是否更新数据',
    requests_recovery    varchar(1)      not null            comment '是否接受恢复执行',
    job_data             blob            null                comment '存放持久化job对象',
    primary key (sched_name, job_name, job_group)
) engine=innodb comment = '任务详细信息表';

-- ----------------------------
-- 2、 存储已配置的 Trigger 的信息
-- ----------------------------
create table QRTZ_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment '触发器的名字',
    trigger_group        varchar(200)    not null            comment '触发器所属组的名字',
    job_name             varchar(200)    not null            comment 'qrtz_job_details表job_name的外键',
    job_group            varchar(200)    not null            comment 'qrtz_job_details表job_group的外键',
    description          varchar(250)    null                comment '相关介绍',
    next_fire_time       bigint(13)      null                comment '上一次触发时间（毫秒）',
    prev_fire_time       bigint(13)      null                comment '下一次触发时间（默认为-1表示不触发）',
    priority             integer         null                comment '优先级',
    trigger_state        varchar(16)     not null            comment '触发器状态',
    trigger_type         varchar(8)      not null            comment '触发器的类型',
    start_time           bigint(13)      not null            comment '开始时间',
    end_time             bigint(13)      null                comment '结束时间',
    calendar_name        varchar(200)    null                comment '日程表名称',
    misfire_instr        smallint(2)     null                comment '补偿执行的策略',
    job_data             blob            null                comment '存放持久化job对象',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, job_name, job_group) references QRTZ_JOB_DETAILS(sched_name, job_name, job_group)
) engine=innodb comment = '触发器详细信息表';

-- ----------------------------
-- 3、 存储简单的 Trigger，包括重复次数，间隔，以及已触发的次数
-- ----------------------------
create table QRTZ_SIMPLE_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    repeat_count         bigint(7)       not null            comment '重复的次数统计',
    repeat_interval      bigint(12)      not null            comment '重复的间隔时间',
    times_triggered      bigint(10)      not null            comment '已经触发的次数',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, trigger_name, trigger_group) references QRTZ_TRIGGERS(sched_name, trigger_name, trigger_group)
) engine=innodb comment = '简单触发器的信息表';

-- ----------------------------
-- 4、 存储 Cron Trigger，包括 Cron 表达式和时区信息
-- ---------------------------- 
create table QRTZ_CRON_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    cron_expression      varchar(200)    not null            comment 'cron表达式',
    time_zone_id         varchar(80)                         comment '时区',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, trigger_name, trigger_group) references QRTZ_TRIGGERS(sched_name, trigger_name, trigger_group)
) engine=innodb comment = 'Cron类型的触发器表';

-- ----------------------------
-- 5、 Trigger 作为 Blob 类型存储(用于 Quartz 用户用 JDBC 创建他们自己定制的 Trigger 类型，JobStore 并不知道如何存储实例的时候)
-- ---------------------------- 
create table QRTZ_BLOB_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    blob_data            blob            null                comment '存放持久化Trigger对象',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, trigger_name, trigger_group) references QRTZ_TRIGGERS(sched_name, trigger_name, trigger_group)
) engine=innodb comment = 'Blob类型的触发器表';

-- ----------------------------
-- 6、 以 Blob 类型存储存放日历信息， quartz可配置一个日历来指定一个时间范围
-- ---------------------------- 
create table QRTZ_CALENDARS (
    sched_name           varchar(120)    not null            comment '调度名称',
    calendar_name        varchar(200)    not null            comment '日历名称',
    calendar             blob            not null            comment '存放持久化calendar对象',
    primary key (sched_name, calendar_name)
) engine=innodb comment = '日历信息表';

-- ----------------------------
-- 7、 存储已暂停的 Trigger 组的信息
-- ---------------------------- 
create table QRTZ_PAUSED_TRIGGER_GRPS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    primary key (sched_name, trigger_group)
) engine=innodb comment = '暂停的触发器表';

-- ----------------------------
-- 8、 存储与已触发的 Trigger 相关的状态信息，以及相联 Job 的执行信息
-- ---------------------------- 
create table QRTZ_FIRED_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    entry_id             varchar(95)     not null            comment '调度器实例id',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    instance_name        varchar(200)    not null            comment '调度器实例名',
    fired_time           bigint(13)      not null            comment '触发的时间',
    sched_time           bigint(13)      not null            comment '定时器制定的时间',
    priority             integer         not null            comment '优先级',
    state                varchar(16)     not null            comment '状态',
    job_name             varchar(200)    null                comment '任务名称',
    job_group            varchar(200)    null                comment '任务组名',
    is_nonconcurrent     varchar(1)      null                comment '是否并发',
    requests_recovery    varchar(1)      null                comment '是否接受恢复执行',
    primary key (sched_name, entry_id)
) engine=innodb comment = '已触发的触发器表';

-- ----------------------------
-- 9、 存储少量的有关 Scheduler 的状态信息，假如是用于集群中，可以看到其他的 Scheduler 实例
-- ---------------------------- 
create table QRTZ_SCHEDULER_STATE (
    sched_name           varchar(120)    not null            comment '调度名称',
    instance_name        varchar(200)    not null            comment '实例名称',
    last_checkin_time    bigint(13)      not null            comment '上次检查时间',
    checkin_interval     bigint(13)      not null            comment '检查间隔时间',
    primary key (sched_name, instance_name)
) engine=innodb comment = '调度器状态表';

-- ----------------------------
-- 10、 存储程序的悲观锁的信息(假如使用了悲观锁)
-- ---------------------------- 
create table QRTZ_LOCKS (
    sched_name           varchar(120)    not null            comment '调度名称',
    lock_name            varchar(40)     not null            comment '悲观锁名称',
    primary key (sched_name, lock_name)
) engine=innodb comment = '存储的悲观锁信息表';

-- ----------------------------
-- 11、 Quartz集群实现同步机制的行锁表
-- ---------------------------- 
create table QRTZ_SIMPROP_TRIGGERS (
    sched_name           varchar(120)    not null            comment '调度名称',
    trigger_name         varchar(200)    not null            comment 'qrtz_triggers表trigger_name的外键',
    trigger_group        varchar(200)    not null            comment 'qrtz_triggers表trigger_group的外键',
    str_prop_1           varchar(512)    null                comment 'String类型的trigger的第一个参数',
    str_prop_2           varchar(512)    null                comment 'String类型的trigger的第二个参数',
    str_prop_3           varchar(512)    null                comment 'String类型的trigger的第三个参数',
    int_prop_1           int             null                comment 'int类型的trigger的第一个参数',
    int_prop_2           int             null                comment 'int类型的trigger的第二个参数',
    long_prop_1          bigint          null                comment 'long类型的trigger的第一个参数',
    long_prop_2          bigint          null                comment 'long类型的trigger的第二个参数',
    dec_prop_1           numeric(13,4)   null                comment 'decimal类型的trigger的第一个参数',
    dec_prop_2           numeric(13,4)   null                comment 'decimal类型的trigger的第二个参数',
    bool_prop_1          varchar(1)      null                comment 'Boolean类型的trigger的第一个参数',
    bool_prop_2          varchar(1)      null                comment 'Boolean类型的trigger的第二个参数',
    primary key (sched_name, trigger_name, trigger_group),
    foreign key (sched_name, trigger_name, trigger_group) references QRTZ_TRIGGERS(sched_name, trigger_name, trigger_group)
) engine=innodb comment = '同步机制的行锁表';

commit;

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
