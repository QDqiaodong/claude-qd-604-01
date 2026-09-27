-- ============================================================================
-- 单车驿站（共享单车 / 公共自行车运营）管理系统 —— 建表 + 种子数据
-- 库：bike_station    字符集：utf8mb4 / utf8mb4_unicode_ci
--
-- 执行时机：mysql 镜像【构建期】执行（见 mysql/Dockerfile 里 COPY 到
--           /docker-entrypoint-initdb.d/01-schema.sql）。
--           后端 spring.jpa.hibernate.ddl-auto=none，表结构以本文件为准。
--
-- 种子数据的口径：5 个业务模块里的【每一条】业务规则，都能在下面这份数据上
-- 被真的触发一次（可行分支 + 拒绝分支都留了样本），改规则时不用另造数据。
-- ============================================================================

SET NAMES utf8mb4;

-- 桩位巡检模块的表、清站模块的两张表先于业务表删除（子表先删，避免悬空引用）
DROP TABLE IF EXISTS fault_handling;
DROP TABLE IF EXISTS inspection_item;
DROP TABLE IF EXISTS inspection_batch;
DROP TABLE IF EXISTS clearance_item;
DROP TABLE IF EXISTS clearance_receiver;
DROP TABLE IF EXISTS clearance_order;
DROP TABLE IF EXISTS work_order;
DROP TABLE IF EXISTS rental;
DROP TABLE IF EXISTS bike;
DROP TABLE IF EXISTS pile;
DROP TABLE IF EXISTS station;
DROP TABLE IF EXISTS worker;
DROP TABLE IF EXISTS bike_user;

-- ---------------------------------------------------------------------------
-- 骑行人（会员）
--   balance 用来触发「余额不足拦借车 / 拦扣费」
-- ---------------------------------------------------------------------------
CREATE TABLE bike_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    phone      VARCHAR(20)  NOT NULL COMMENT '手机号，唯一',
    nickname   VARCHAR(50)  NOT NULL COMMENT '昵称',
    balance    DECIMAL(10, 2) NOT NULL COMMENT '账户余额（元）',
    status     VARCHAR(16)  NOT NULL COMMENT '账号状态：NORMAL 正常 / FROZEN 冻结',
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '骑行人';

-- ---------------------------------------------------------------------------
-- 站点（驿站）
--   capacity 触发「站点容量上限」；status=CLOSED 触发「站点停用禁借还」
-- ---------------------------------------------------------------------------
CREATE TABLE station (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32)  NOT NULL COMMENT '站点编号，唯一，形如 S001',
    name       VARCHAR(64)  NOT NULL COMMENT '站点名称',
    district   VARCHAR(32)  NOT NULL COMMENT '所在行政区',
    capacity   INT          NOT NULL COMMENT '可停放车辆数上限',
    status     VARCHAR(16)  NOT NULL COMMENT '站点状态：OPEN 运营中 / CLOSED 已停用',
    longitude  DECIMAL(10, 6) NULL,
    latitude   DECIMAL(10, 6) NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_station_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '站点';

-- ---------------------------------------------------------------------------
-- 桩位
--   一桩一车的落点在 bike.pile_id 上的唯一索引（MySQL 唯一索引允许多个 NULL，
--   所以「空桩」可以无限多，「占用」只允许一辆车）。pile.status 只是快照，
--   真正的占用关系以 bike.pile_id 为准，两者在同一个事务里一起改。
-- ---------------------------------------------------------------------------
CREATE TABLE pile (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    station_id BIGINT      NOT NULL COMMENT '所属站点',
    pile_no    VARCHAR(16) NOT NULL COMMENT '站内桩位号，形如 P1',
    status     VARCHAR(24) NOT NULL COMMENT '桩位状态：IDLE 空闲 / OCCUPIED 占用 / DISABLED 停用 / PENDING_ISOLATED 待隔离 / ISOLATED 已隔离',
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pile_station_no (station_id, pile_no),
    KEY idx_pile_station (station_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '桩位';

-- ---------------------------------------------------------------------------
-- 车辆台账
--   code 唯一；status 是状态机（在桩/骑行中/维修中/报废）；
--   pile_id 唯一 → 一桩至多一车；station_id 用于站点容量计数。
-- ---------------------------------------------------------------------------
CREATE TABLE bike (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32) NOT NULL COMMENT '车辆编号，唯一，形如 BK-1001',
    model        VARCHAR(32) NOT NULL COMMENT '车型',
    status       VARCHAR(16) NOT NULL COMMENT 'PILE 在桩 / RIDING 骑行中 / REPAIR 维修中 / SCRAPPED 报废',
    station_id   BIGINT      NULL COMMENT '在桩/维修时所在站点；骑行中与报废后为 NULL',
    pile_id      BIGINT      NULL COMMENT '在桩时占用的桩位；骑行中/维修中/报废为 NULL',
    total_rides  INT         NOT NULL COMMENT '累计骑行次数',
    scrap_reason VARCHAR(128) NULL COMMENT '报废原因（仅报废车有值）',
    created_at   DATETIME    NOT NULL,
    updated_at   DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bike_code (code),
    UNIQUE KEY uk_bike_pile (pile_id),
    KEY idx_bike_station (station_id),
    KEY idx_bike_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '车辆台账';

-- ---------------------------------------------------------------------------
-- 租借订单
--   active_bike_id 是生成列：只在 status='RIDING' 时有值，配唯一索引
--   → 数据库层面硬保证「同一辆车同时只能有一张在途单」。
-- ---------------------------------------------------------------------------
CREATE TABLE rental (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(32) NOT NULL COMMENT '订单号，唯一',
    user_id          BIGINT      NOT NULL COMMENT '借车人',
    bike_id          BIGINT      NOT NULL COMMENT '车辆',
    start_station_id BIGINT      NOT NULL COMMENT '借车站点',
    start_pile_id    BIGINT      NOT NULL COMMENT '借车桩位',
    end_station_id   BIGINT      NULL COMMENT '还车站点（未还车为 NULL）',
    end_pile_id      BIGINT      NULL COMMENT '还车桩位（未还车为 NULL）',
    start_time       DATETIME    NOT NULL,
    end_time         DATETIME    NULL,
    duration_minutes INT         NULL COMMENT '骑行时长（分钟）',
    amount           DECIMAL(10, 2) NULL COMMENT '结算金额（元）',
    status           VARCHAR(16) NOT NULL COMMENT 'RIDING 骑行中 / SETTLED 已结算',
    created_at       DATETIME    NOT NULL,
    active_bike_id   BIGINT GENERATED ALWAYS AS (IF(status = 'RIDING', bike_id, NULL)) STORED
                     COMMENT '生成列：仅骑行中订单有值，配合唯一索引实现「一车一在途单」',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rental_order_no (order_no),
    UNIQUE KEY uk_rental_active_bike (active_bike_id),
    KEY idx_rental_bike (bike_id),
    KEY idx_rental_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '租借订单';

-- ---------------------------------------------------------------------------
-- 维修工
--   station_id 是「驻站」：应急清站时只有「在岗（ON_DUTY）且名下没有
--   未完成工单（REPORTED / ASSIGNED）」的驻站维修工，才让他所在的站点
--   具备接收故障车的能力。允许为 NULL（无固定驻站，不参与清站接收）。
-- ---------------------------------------------------------------------------
CREATE TABLE worker (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(32) NOT NULL COMMENT '姓名',
    phone      VARCHAR(20) NOT NULL COMMENT '手机号，唯一',
    skill      VARCHAR(32) NOT NULL COMMENT '技能方向',
    status     VARCHAR(16) NOT NULL COMMENT 'ON_DUTY 在岗 / OFF_DUTY 休息',
    station_id BIGINT      NULL COMMENT '驻站站点；应急清站判定故障车接收能力用',
    PRIMARY KEY (id),
    UNIQUE KEY uk_worker_phone (phone),
    KEY idx_worker_station (station_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '维修工';

-- ---------------------------------------------------------------------------
-- 运维工单
--   状态机 REPORTED → ASSIGNED → FIXED → CLOSED；
--   (worker_id, plan_start, plan_end) 支撑「同一维修工时段不重叠」。
-- ---------------------------------------------------------------------------
CREATE TABLE work_order (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32) NOT NULL COMMENT '工单号，唯一',
    bike_id     BIGINT      NOT NULL COMMENT '故障车辆',
    fault_type  VARCHAR(32) NOT NULL COMMENT '故障类型',
    level       VARCHAR(16) NOT NULL COMMENT 'LOW 低 / MID 中 / HIGH 高',
    status      VARCHAR(16) NOT NULL COMMENT 'REPORTED 已上报 / ASSIGNED 已派单 / FIXED 已修复 / CLOSED 已关闭',
    worker_id   BIGINT      NULL COMMENT '承修维修工（未派单为 NULL）',
    plan_start  DATETIME    NULL COMMENT '计划开始时间（未派单为 NULL）',
    plan_end    DATETIME    NULL COMMENT '计划结束时间（未派单为 NULL）',
    report_time DATETIME    NOT NULL COMMENT '上报时间',
    assign_time DATETIME    NULL COMMENT '派单时间',
    fix_time    DATETIME    NULL COMMENT '修复时间',
    fix_note    VARCHAR(255) NULL COMMENT '维修说明',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_bike (bike_id),
    KEY idx_order_worker_plan (worker_id, plan_start, plan_end),
    KEY idx_order_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '运维工单';

-- ---------------------------------------------------------------------------
-- 应急清站单（模块五）
--   状态机：PENDING 待执行 → RUNNING 执行中 → COMPLETED 已完成 /
--           PARTIALLY_BLOCKED 部分受阻（可反复执行，最后一次执行会原地推进）；
--           PENDING → CANCELLED 已撤销（只允许在尚未迁出任何车辆时撤销）。
--   真正停用源站只在 COMPLETED 那一刻发生；受阻单绝不把源站标成已清空。
--   executing_token / executing_at 是「幂等执行」的串行化锚点：
--   重复点击、刷新重提、双人同时点执行，只有一个请求真正在跑，
--   另一个请求拿到的是最新进度；每辆车最多成功迁出一次。
-- ---------------------------------------------------------------------------
CREATE TABLE clearance_order (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    order_no            VARCHAR(32)  NOT NULL COMMENT '清站单号，唯一，形如 CS2026...',
    source_station_id   BIGINT       NOT NULL COMMENT '要停用/清空的源站',
    status              VARCHAR(24)  NOT NULL COMMENT 'PENDING 待执行 / RUNNING 执行中 / PARTIALLY_BLOCKED 部分受阻 / COMPLETED 已完成 / CANCELLED 已撤销',
    total_count         INT          NOT NULL COMMENT '建单时源站待迁车辆总数（含暂时无处可去的车）',
    planned_count       INT          NOT NULL COMMENT '已有计划去向的车辆数',
    migrated_count      INT          NOT NULL COMMENT '已成功迁出的车辆数',
    blocked_count       INT          NOT NULL COMMENT '受阻/无处安置的车辆数',
    plan_remark         VARCHAR(255) NULL COMMENT '方案生成说明（为什么有车没地方去）',
    executing_token     VARCHAR(64)  NULL COMMENT '当前执行令牌：同单并发执行时只有一个请求拿得到',
    executing_at        DATETIME     NULL COMMENT '本次执行开始时间（卡死兜底可识别）',
    last_execute_time   DATETIME     NULL COMMENT '最近一次执行时间',
    last_execute_result VARCHAR(255) NULL COMMENT '最近一次执行的结果说明',
    cancelled_time      DATETIME     NULL COMMENT '撤销时间',
    completed_time      DATETIME     NULL COMMENT '完成（源站真正停用）时间',
    created_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_clearance_order_no (order_no),
    KEY idx_clearance_source (source_station_id),
    KEY idx_clearance_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '应急清站单';

-- ---------------------------------------------------------------------------
-- 清站单接收站
--   一张单选一个或多个接收站，seq 记录选择顺序；
--   方案生成时按「故障车 → 具备在岗维修工且无工单冲突的接收站、
--   普通车 → 就近」的原则在这些站里分配。
-- ---------------------------------------------------------------------------
CREATE TABLE clearance_receiver (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    clearance_id BIGINT      NOT NULL COMMENT '所属清站单',
    station_id   BIGINT      NOT NULL COMMENT '接收站',
    seq          INT         NOT NULL COMMENT '选择顺序（从 1 开始）',
    created_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_clearance_receiver (clearance_id, station_id),
    KEY idx_receiver_clearance (clearance_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '清站单接收站';

-- ---------------------------------------------------------------------------
-- 清站车辆明细（每辆车一行，是「绝不半迁移」的落点账本）
--   item_status：
--     PLANNED    已排好计划去向，等待执行
--     UNPLACED   暂时无处安置（接收能力不足），允许保存待执行
--     MIGRATED   已成功迁出（actual_* 记录真实落点，只成功一次）
--     BLOCKED    执行受阻（车被借走/目标桩被占/接收站停用…），保留在单内，不回滚已迁出的车
--   车辆类型 bike_kind：NORMAL 普通车 / FAULT 故障车（维修中，或挂未完成工单）
-- ---------------------------------------------------------------------------
CREATE TABLE clearance_item (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    clearance_id       BIGINT       NOT NULL COMMENT '所属清站单',
    bike_id            BIGINT       NOT NULL COMMENT '待迁车辆',
    bike_kind          VARCHAR(16)  NOT NULL COMMENT 'NORMAL 普通车 / FAULT 故障车',
    item_status        VARCHAR(16)  NOT NULL COMMENT 'PLANNED / UNPLACED / MIGRATED / BLOCKED',
    plan_station_id    BIGINT       NULL COMMENT '计划接收站（无处安置为 NULL）',
    plan_pile_id       BIGINT       NULL COMMENT '计划桩位（维修区车辆与无处安置为 NULL）',
    actual_station_id  BIGINT       NULL COMMENT '实际迁入站',
    actual_pile_id     BIGINT       NULL COMMENT '实际占用桩位',
    fail_reason        VARCHAR(255) NULL COMMENT '受阻原因 / 无处安置说明',
    migrated_at        DATETIME     NULL COMMENT '迁出成功时间',
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_clearance_bike (clearance_id, bike_id),
    KEY idx_clearance_item_status (clearance_id, item_status),
    KEY idx_clearance_item_bike (bike_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '清站车辆明细';

-- ---------------------------------------------------------------------------
-- 桩位巡检批次（模块六）
--   建单时把【本批次要查的桩位清单】与【当时每个桩位的占车情况】一次性快照进
--   inspection_item —— 之后站点新增桩位不会悄悄混进旧批次；巡检期间车辆被
--   借走/还回/调拨也不改写快照，只影响提交检查结果时「待隔离 vs 已隔离」的判定。
--   状态机：IN_PROGRESS 巡检中 → COMPLETED 已完成（全部桩位都登记了检查结果）。
--   同一站点允许有任意多历史批次，批次之间互不影响。
-- ---------------------------------------------------------------------------
CREATE TABLE inspection_batch (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no     VARCHAR(32)  NOT NULL COMMENT '批次号，唯一，形如 JC2026...',
    station_id   BIGINT       NOT NULL COMMENT '巡检站点',
    status       VARCHAR(16)  NOT NULL COMMENT 'IN_PROGRESS 巡检中 / COMPLETED 已完成',
    total_count  INT          NOT NULL COMMENT '建单时快照的桩位总数（固定，不随后续加桩变化）',
    checked_count INT         NOT NULL COMMENT '已登记检查结果的桩位数',
    fault_count  INT          NOT NULL COMMENT '检查结果为故障的桩位数',
    remark       VARCHAR(255) NULL COMMENT '批次备注',
    created_by   VARCHAR(32)  NOT NULL COMMENT '建单人（运营人员姓名）',
    created_at   DATETIME     NOT NULL,
    completed_at DATETIME     NULL COMMENT '全部桩位检查完成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_batch_no (batch_no),
    KEY idx_inspection_station (station_id),
    KEY idx_inspection_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '桩位巡检批次';

-- ---------------------------------------------------------------------------
-- 巡检批次逐桩明细（一桩一行，是批次的固定桩位清单 + 当时占车快照）
--   snapshot_occupied：建单当时该桩是否占车；snapshot_bike_id 记录当时挂着的车。
--   result：PENDING 待检查 / OK 正常 / FAULT 故障。
--   rev 是乐观锁版本：两名运营人员几乎同时登记同一个桩，或检查提交与车辆离桩、
--   复验相撞时，旧页面拿着旧 rev 提交会被拒（409 冲突），必须按最新状态重来，
--   已生效的车辆落点与巡检记录绝不被旧页面覆盖。
-- ---------------------------------------------------------------------------
CREATE TABLE inspection_item (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id           BIGINT       NOT NULL COMMENT '所属巡检批次',
    pile_id            BIGINT       NOT NULL COMMENT '被检查的桩位',
    pile_no            VARCHAR(16)  NOT NULL COMMENT '建单时的桩位号快照（桩位号可能改名）',
    snapshot_occupied  TINYINT      NOT NULL COMMENT '建单当时是否占车：1 是 / 0 否',
    snapshot_bike_id   BIGINT       NULL COMMENT '建单当时占着该桩的车辆',
    snapshot_bike_code VARCHAR(32)  NULL COMMENT '建单当时车辆编号快照（便于直接展示）',
    result             VARCHAR(16)  NOT NULL COMMENT 'PENDING 待检查 / OK 正常 / FAULT 故障',
    lock_status        VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT '桩锁检查：UNKNOWN 未查 / NORMAL 正常 / FAULT 故障',
    power_status       VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT '供电检查：UNKNOWN 未查 / NORMAL 正常 / FAULT 故障',
    comm_status        VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN' COMMENT '通信检查：UNKNOWN 未查 / NORMAL 正常 / FAULT 故障',
    fault_level        VARCHAR(16)  NULL COMMENT '故障等级：LOW 低 / MID 中 / HIGH 高（仅故障有值）',
    fault_desc         VARCHAR(255) NULL COMMENT '故障描述 / 检查备注',
    isolated_mode      VARCHAR(24)  NULL COMMENT '故障桩的隔离形态：ISOLATED 已隔离 / PENDING_ISOLATED 待隔离（仅故障有值）',
    checker_name       VARCHAR(32)  NULL COMMENT '检查人',
    checked_at         DATETIME     NULL COMMENT '检查时间',
    rev                INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本：每次修改 +1，旧版本提交判冲突',
    created_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_item (batch_id, pile_id),
    KEY idx_inspection_item_pile (pile_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '巡检批次逐桩明细';

-- ---------------------------------------------------------------------------
-- 桩位故障处置单（模块六）
--   由「逐桩检查发现故障」自动开立，复验是唯一的出口。
--   状态机：FH_PENDING 待处置 → FH_HANDLED 已处置待复验 →
--           FH_RECHECK_PASS 复验通过（终态，桩位恢复）/
--           FH_RECHECK_REJECTED 复验不通过（保留原因，继续隔离，可再次处置/复验）。
--   active_pile_id 是生成列：处置未完成（非 FH_RECHECK_PASS）时等于 pile_id，
--   配唯一索引 → 数据库层面硬保证「同一桩位不能同时存在两条尚未完成的故障处置」。
--
--   隔离口径（安全隔离优先）：
--     空桩故障立即 ISOLATED，借车/还车/调拨一律不再可选；
--     桩上有车则 PENDING_ISOLATED：不清车辆落点，车仍可被借走或调出，
--     但任何新车不能进入；车一离开（借车/调拨/送修走 releasePile）自动转 ISOLATED。
--   复验通过时桩位按【真实占车情况】恢复 IDLE/OCCUPIED，不接受页面手工指定。
-- ---------------------------------------------------------------------------
CREATE TABLE fault_handling (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    handling_no        VARCHAR(32)  NOT NULL COMMENT '处置单号，唯一，形如 FH2026...',
    pile_id            BIGINT       NOT NULL COMMENT '故障桩位',
    station_id         BIGINT       NOT NULL COMMENT '桩位所属站点',
    batch_id           BIGINT       NOT NULL COMMENT '来源巡检批次',
    item_id            BIGINT       NOT NULL COMMENT '来源巡检明细',
    bike_id            BIGINT       NULL COMMENT '建单/检查时桩上的车辆（待隔离样本，可为空）',
    fault_level        VARCHAR(16)  NOT NULL COMMENT '故障等级：LOW / MID / HIGH',
    fault_desc         VARCHAR(255) NULL COMMENT '故障描述',
    status             VARCHAR(24)  NOT NULL COMMENT 'FH_PENDING 待处置 / FH_HANDLED 已处置待复验 / FH_RECHECK_PASS 复验通过 / FH_RECHECK_REJECTED 复验不通过',
    handler_name       VARCHAR(32)  NULL COMMENT '处置人',
    handle_note        VARCHAR(255) NULL COMMENT '处置说明',
    handled_at         DATETIME     NULL COMMENT '处置时间',
    rechecker_name     VARCHAR(32)  NULL COMMENT '复验人',
    recheck_pass       TINYINT      NULL COMMENT '复验结论：1 通过 / 0 不通过',
    recheck_note       VARCHAR(255) NULL COMMENT '复验说明（不通过时必须填写原因）',
    rechecked_at       DATETIME     NULL COMMENT '最近一次复验时间',
    rev                INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本：处置/复验相撞时旧提交判冲突',
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME     NULL,
    active_pile_id     BIGINT GENERATED ALWAYS AS (IF(status = 'FH_RECHECK_PASS', NULL, pile_id)) STORED
                       COMMENT '生成列：处置未完成时等于桩位，配合唯一索引实现「一桩至多一条未完成处置」',
    PRIMARY KEY (id),
    UNIQUE KEY uk_handling_no (handling_no),
    UNIQUE KEY uk_active_fault_pile (active_pile_id),
    KEY idx_handling_pile (pile_id),
    KEY idx_handling_station_status (station_id, status),
    KEY idx_handling_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '桩位故障处置单';


-- ===========================================================================
-- 种子数据
-- ===========================================================================

-- ---- 骑行人 ---------------------------------------------------------------
-- 1 号余额充足（可走通借车→还车）；2 号余额 0.50 元，低于起步价 1.50 元
-- → 触发「余额不足拦借车」；3 号余额充足。
INSERT INTO bike_user (id, phone, nickname, balance, status, created_at) VALUES
(1, '13800000001', '阿明', 30.00, 'NORMAL', '2026-08-01 09:00:00'),
(2, '13800000002', '阿红',  0.50, 'NORMAL', '2026-08-02 10:00:00'),
(3, '13800000003', '阿刚', 12.00, 'NORMAL', '2026-08-03 11:00:00');

-- ---- 站点 -----------------------------------------------------------------
-- S004 停用 → 触发「站点停用禁借还」；
-- S005 容量只有 2，站里已经停了 2 辆（BK-1008 / BK-1009），但还留着两个空桩位
--   → 只有这种「桩位空着、车位数却满了」的站点才能真的触发「站点容量超限」，
--     否则会先被「一桩一车」拦掉，容量这条规则就永远试不出来。
INSERT INTO station (id, code, name, district, capacity, status, longitude, latitude, created_at) VALUES
(1, 'S001', '中关村驿站',   '海淀区', 6, 'OPEN',   116.316000, 39.983000, '2026-07-01 08:00:00'),
(2, 'S002', '五道口驿站',   '海淀区', 4, 'OPEN',   116.337000, 39.992000, '2026-07-01 08:00:00'),
(3, 'S003', '西二旗驿站',   '昌平区', 5, 'OPEN',   116.308000, 40.056000, '2026-07-01 08:00:00'),
(4, 'S004', '国贸驿站',     '朝阳区', 3, 'CLOSED', 116.461000, 39.908000, '2026-07-01 08:00:00'),
(5, 'S005', '望京驿站',     '朝阳区', 2, 'OPEN',   116.470000, 39.996000, '2026-07-01 08:00:00');

-- ---- 桩位 -----------------------------------------------------------------
-- 106 号桩位停用 → 触发「停用桩位不能借车/还车/调拨」。
-- 123 号（S001 / P7）放模块五的故障车 BK-1010：清 S001 时它按「故障车优先」
-- 必须送到具备在岗维修工且没有未完成工单冲突的站点（初始数据里只有 S003）。
-- 模块六（桩位巡检）种子：
--   103 号 P3 已隔离（空桩故障，立即隔离）→ 看板「已隔离」；
--   104 号 P4 待隔离（桩上 BK-1003，车仍可被借走/调出，车一离开自动转已隔离，
--     期间任何新车不能进入）；
--   101 号 P1 待隔离 + 处置已完成待复验（车 9-23 被临时调出过又还回，复验前
--     任何新车不能进入，复验通过后按真实占车恢复）；
--   107 号 P2 复验通过后恢复占用（历史批次样本，当前无隔离效果）。
INSERT INTO pile (id, station_id, pile_no, status, created_at) VALUES
(101, 1, 'P1', 'PENDING_ISOLATED', '2026-07-01 08:00:00'),
(102, 1, 'P2', 'OCCUPIED', '2026-07-01 08:00:00'),
(103, 1, 'P3', 'ISOLATED', '2026-07-01 08:00:00'),
(104, 1, 'P4', 'PENDING_ISOLATED', '2026-07-01 08:00:00'),
(105, 1, 'P5', 'IDLE',     '2026-07-01 08:00:00'),
(106, 1, 'P6', 'DISABLED', '2026-07-01 08:00:00'),
(123, 1, 'P7', 'OCCUPIED', '2026-07-01 08:00:00'),
(107, 2, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(108, 2, 'P2', 'OCCUPIED', '2026-07-01 08:00:00'),
(109, 2, 'P3', 'IDLE',     '2026-07-01 08:00:00'),
(110, 2, 'P4', 'IDLE',     '2026-07-01 08:00:00'),
(111, 3, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(112, 3, 'P2', 'IDLE',     '2026-07-01 08:00:00'),
(113, 3, 'P3', 'IDLE',     '2026-07-01 08:00:00'),
(114, 3, 'P4', 'IDLE',     '2026-07-01 08:00:00'),
(115, 3, 'P5', 'IDLE',     '2026-07-01 08:00:00'),
(116, 4, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(117, 4, 'P2', 'IDLE',     '2026-07-01 08:00:00'),
(118, 4, 'P3', 'IDLE',     '2026-07-01 08:00:00'),
(119, 5, 'P1', 'OCCUPIED', '2026-07-01 08:00:00'),
(120, 5, 'P2', 'OCCUPIED', '2026-07-01 08:00:00'),
(121, 5, 'P3', 'IDLE',     '2026-07-01 08:00:00'),
(122, 5, 'P4', 'IDLE',     '2026-07-01 08:00:00');

-- ---- 车辆 -----------------------------------------------------------------
-- 在桩 10 辆（其中 1007 停在已停用的 S004、1008/1009 停满 S005、
--   1010 是停在 S001 的故障车，挂着一张未派单工单 → 模块五「故障车优先」样本）
-- 骑行中 2 辆（2001/2002，各有一张在途单；2002 的在途单从 S001 借走
--   → 清 S001 时「源站存在在途租借」无法立刻完成，得等它还到别的站）
-- 维修中 1 辆（3001，挂 S003 但不占桩）
-- 报废 1 辆（4001，终态，无站点无桩位）
-- 模块六巡检痕迹：1003 在 9-20 巡检发现 103 号桩故障后被骑走（桩自动转已隔离），
--   还车时停到当时正常的 104 号桩，9-26 巡检又发现 104 故障，故 1003 现在挂在
--   「待隔离」的 104 号桩上：车仍可被借走/调出，但任何新车不能再进入该桩。
INSERT INTO bike (id, code, model, status, station_id, pile_id, total_rides, scrap_reason, created_at, updated_at) VALUES
(1001, 'BK-1001', '青桔经典款',  'PILE',     1, 101,  42, NULL, '2026-06-01 08:00:00', '2026-09-20 18:00:00'),
(1002, 'BK-1002', '青桔经典款',  'PILE',     1, 102,  37, NULL, '2026-06-01 08:00:00', '2026-09-20 18:00:00'),
(1003, 'BK-1003', '青桔轻骑款',  'PILE',     1, 104,  56, NULL, '2026-06-01 08:00:00', '2026-09-21 09:40:00'),
(1004, 'BK-1004', '哈啰电助力',  'PILE',     2, 107,  88, NULL, '2026-06-02 08:00:00', '2026-09-20 18:00:00'),
(1005, 'BK-1005', '哈啰电助力',  'PILE',     2, 108,  61, NULL, '2026-06-02 08:00:00', '2026-09-20 18:00:00'),
(1006, 'BK-1006', '美团小黄车',  'PILE',     3, 111,  73, NULL, '2026-06-03 08:00:00', '2026-09-20 18:00:00'),
(1007, 'BK-1007', '美团小黄车',  'PILE',     4, 116,  19, NULL, '2026-06-03 08:00:00', '2026-09-20 18:00:00'),
(1008, 'BK-1008', '摩拜经典款',  'PILE',     5, 119,  26, NULL, '2026-06-04 08:00:00', '2026-09-20 18:00:00'),
(1009, 'BK-1009', '摩拜经典款',  'PILE',     5, 120,  31, NULL, '2026-06-04 08:00:00', '2026-09-20 18:00:00'),
(1010, 'BK-1010', '青桔轻骑款',  'PILE',     1, 123,   8, NULL, '2026-06-07 08:00:00', '2026-09-22 09:30:00'),
(2001, 'BK-2001', '青桔经典款',  'RIDING',   NULL, NULL, 64, NULL, '2026-06-05 08:00:00', '2026-09-21 19:00:00'),
(2002, 'BK-2002', '哈啰电助力',  'RIDING',   NULL, NULL, 47, NULL, '2026-06-05 08:00:00', '2026-09-21 20:30:00'),
(3001, 'BK-3001', '美团小黄车',  'REPAIR',   3, NULL,  58, NULL, '2026-06-06 08:00:00', '2026-09-18 15:10:00'),
(4001, 'BK-4001', '摩拜经典款',  'SCRAPPED', NULL, NULL, 132, '车架断裂，判定报废', '2026-05-20 08:00:00', '2026-08-15 10:00:00');

-- ---- 租借订单 -------------------------------------------------------------
-- 4 张已结算单，覆盖计费四档：25 分钟 1.50 元 / 95 分钟 3.50 元 /
-- 200 分钟 6.50 元 / 700 分钟封顶 20.00 元。
-- 2 张在途单（RIDING），用于触发「同一辆车已有在途订单」。
INSERT INTO rental (id, order_no, user_id, bike_id, start_station_id, start_pile_id,
                    end_station_id, end_pile_id, start_time, end_time,
                    duration_minutes, amount, status, created_at) VALUES
(1, 'R20260901001', 1, 1002, 1, 102, 2, 109, '2026-09-01 08:10:00', '2026-09-01 08:35:00',  25,  1.50, 'SETTLED', '2026-09-01 08:10:00'),
(2, 'R20260901002', 1, 1003, 1, 103, 3, 112, '2026-09-01 09:00:00', '2026-09-01 10:35:00',  95,  3.50, 'SETTLED', '2026-09-01 09:00:00'),
(3, 'R20260901003', 3, 1004, 2, 107, 1, 104, '2026-09-10 18:00:00', '2026-09-10 21:20:00', 200,  6.50, 'SETTLED', '2026-09-10 18:00:00'),
(4, 'R20260901004', 3, 1006, 3, 111, 2, 110, '2026-09-15 07:00:00', '2026-09-15 18:40:00', 700, 20.00, 'SETTLED', '2026-09-15 07:00:00'),
(5, 'R20260901005', 1, 2001, 3, 112, NULL, NULL, '2026-09-21 19:00:00', NULL, NULL, NULL, 'RIDING', '2026-09-21 19:00:00'),
(6, 'R20260901006', 3, 2002, 1, 105, NULL, NULL, '2026-09-21 20:30:00', NULL, NULL, NULL, 'RIDING', '2026-09-21 20:30:00');

-- ---- 维修工 ---------------------------------------------------------------
-- 3 号休息中 → 触发「休息的维修工不能派单」。
-- station_id 是驻站（模块五应急清站用）：
--   1 号驻 S001、在岗，但名下有 1 号未完成派单 → S001 不算「无工单冲突」，接不了故障车；
--   2 号驻 S003、在岗、名下没有未完工工单 → S003 具备故障车接收能力；
--   3 号驻 S002 但休息中 → S002 没有在岗维修工，接不了故障车（只能接普通车）。
INSERT INTO worker (id, name, phone, skill, status, station_id) VALUES
(1, '张师傅', '13800001001', '车锁与传动', 'ON_DUTY',  1),
(2, '李师傅', '13800001002', '电控与电池', 'ON_DUTY',  3),
(3, '王师傅', '13800001003', '轮胎与刹车', 'OFF_DUTY', 2);

-- ---- 运维工单 -------------------------------------------------------------
-- 1 号已派单给张师傅 09:00~11:00 → 触发「同维修工时段不重叠」
-- 2 号待派单 → 可走通派单→修复→关闭
-- 3 号已修复 / 4 号已关闭 → 已完成的工单不再占用维修工时段的样本
-- 5 号是 BK-1010 的未派单故障（REPORTED）→ 模块五里它被认定为故障车，
--   清 S001 时必须优先送到「有在岗维修工且没有未完成工单冲突」的站点
INSERT INTO work_order (id, order_no, bike_id, fault_type, level, status, worker_id,
                        plan_start, plan_end, report_time, assign_time, fix_time, fix_note) VALUES
(1, 'WO20260901001', 2001, '智能锁无法上锁', 'HIGH', 'ASSIGNED', 1,
    '2026-09-22 09:00:00', '2026-09-22 11:00:00', '2026-09-21 19:20:00', '2026-09-21 19:40:00', NULL, NULL),
(2, 'WO20260901002', 2002, '后刹车异响',     'MID',  'REPORTED', NULL,
    NULL, NULL, '2026-09-21 20:45:00', NULL, NULL, NULL),
(3, 'WO20260901003', 3001, '车架焊点开裂',   'HIGH', 'FIXED',    2,
    '2026-09-18 14:00:00', '2026-09-18 15:30:00', '2026-09-17 09:00:00', '2026-09-17 10:00:00', '2026-09-18 15:10:00', '补焊并做防锈处理'),
(4, 'WO20260901004', 1009, '脚踏板松动',     'LOW',  'CLOSED',   1,
    '2026-09-05 09:00:00', '2026-09-05 10:00:00', '2026-09-04 16:00:00', '2026-09-04 16:30:00', '2026-09-05 09:50:00', '紧固并更换销钉'),
(5, 'WO20260922005', 1010, '车把转向卡滞',   'MID',  'REPORTED', NULL,
    NULL, NULL, '2026-09-22 09:30:00', NULL, NULL, NULL);

-- ---- 桩位巡检批次（模块六） ----------------------------------------------
-- 批次 1（S001，已完成）：9-20 巡检 P1~P5。
--   P3 空桩桩锁故障立即隔离；P1 供电故障当时有车 BK-1001 → 待隔离（不清落点，
--   车始终没被骑走），9-23 完成处置，目前待复验；P2/P4/P5 正常
--   （P4 是 9-26 新一轮巡检才发现故障）。
-- 批次 2（S002，已完成）：P1 通信故障，复验通过后恢复为占用（历史批次样本）。
-- 批次 3（S001，巡检中）：P4/P6/P7 三个桩，P4 发现桩锁故障时桩上仍有 BK-1003
--   → 进入「待隔离」（不清车辆落点）；P6 已查正常（停用桩只记录、不改状态）；P7 待检查。
INSERT INTO inspection_batch (id, batch_no, station_id, status, total_count, checked_count,
                              fault_count, remark, created_by, created_at, completed_at) VALUES
(1, 'JC20260920001', 1, 'COMPLETED', 5, 5, 2, '周度例行巡检', '赵运营',
    '2026-09-20 08:30:00', '2026-09-20 11:10:00'),
(2, 'JC20260921002', 2, 'COMPLETED', 2, 2, 1, '五道口通信信号复查', '钱运营',
    '2026-09-21 09:00:00', '2026-09-25 16:20:00'),
(3, 'JC20260926003', 1, 'IN_PROGRESS', 3, 2, 1, '周度例行巡检（进行中）', '赵运营',
    '2026-09-26 09:00:00', NULL);

INSERT INTO inspection_item (id, batch_id, pile_id, pile_no, snapshot_occupied, snapshot_bike_id,
                             snapshot_bike_code, result, lock_status, power_status, comm_status,
                             fault_level, fault_desc, isolated_mode, checker_name, checked_at,
                             rev, created_at) VALUES
-- 批次 1
(1, 1, 101, 'P1', 1, 1001, 'BK-1001', 'FAULT',  'NORMAL', 'FAULT', 'NORMAL',
    'HIGH', '供电模块无输出，桩锁无法正常取电', 'PENDING_ISOLATED', '赵运营', '2026-09-20 09:15:00', 2, '2026-09-20 08:30:00'),
(2, 1, 102, 'P2', 1, 1002, 'BK-1002', 'OK',     'NORMAL', 'NORMAL', 'NORMAL',
    NULL, '三项检查均正常', NULL, '赵运营', '2026-09-20 09:40:00', 1, '2026-09-20 08:30:00'),
(3, 1, 103, 'P3', 0, NULL, NULL, 'FAULT', 'FAULT',  'NORMAL', 'NORMAL',
    'HIGH', '桩锁卡死无法开合，空桩立即隔离', 'ISOLATED', '赵运营', '2026-09-20 10:05:00', 1, '2026-09-20 08:30:00'),
(4, 1, 104, 'P4', 0, NULL, NULL, 'OK',     'NORMAL', 'NORMAL', 'NORMAL',
    NULL, '检查时桩位空闲，一切正常', NULL, '赵运营', '2026-09-20 10:30:00', 1, '2026-09-20 08:30:00'),
(5, 1, 105, 'P5', 0, NULL, NULL, 'OK',     'NORMAL', 'NORMAL', 'NORMAL',
    NULL, NULL, NULL, '赵运营', '2026-09-20 11:10:00', 1, '2026-09-20 08:30:00'),
-- 批次 2
(6, 2, 107, 'P1', 1, 1004, 'BK-1004', 'FAULT',  'NORMAL', 'NORMAL', 'FAULT',
    'MID', '通信模块离线，平台读不到桩状态', 'PENDING_ISOLATED', '钱运营', '2026-09-21 09:40:00', 3, '2026-09-21 09:00:00'),
(7, 2, 108, 'P2', 1, 1005, 'BK-1005', 'OK',     'NORMAL', 'NORMAL', 'NORMAL',
    NULL, NULL, NULL, '钱运营', '2026-09-21 10:05:00', 1, '2026-09-21 09:00:00'),
-- 批次 3（进行中）
(8, 3, 104, 'P4', 1, 1003, 'BK-1003', 'FAULT',  'FAULT', 'NORMAL', 'NORMAL',
    'HIGH', '桩锁无法落锁，桩上仍有车，先待隔离，禁止新车进入', 'PENDING_ISOLATED', '赵运营', '2026-09-26 09:30:00', 1, '2026-09-26 09:00:00'),
(9, 3, 106, 'P6', 0, NULL, NULL, 'OK',     'NORMAL', 'NORMAL', 'NORMAL',
    NULL, '停用桩仅登记检查结果，维持停用状态', NULL, '赵运营', '2026-09-26 09:50:00', 1, '2026-09-26 09:00:00'),
(10, 3, 123, 'P7', 1, 1010, 'BK-1010', 'PENDING', 'UNKNOWN', 'UNKNOWN', 'UNKNOWN',
    NULL, NULL, NULL, NULL, NULL, 0, '2026-09-26 09:00:00');

-- ---- 桩位故障处置（模块六） ----------------------------------------------
-- 处置单 1（桩 103，空桩）：待处置，桩位维持已隔离；
-- 处置单 2（桩 101）：已处置待复验，车 9-22 调出后桩位已自动转已隔离；
-- 处置单 3（桩 104，批次 3）：待处置，桩位「待隔离」，BK-1003 仍挂在桩上；
-- 处置单 4（桩 107）：复验通过（终态），恢复时按真实占车恢复为占用。
INSERT INTO fault_handling (id, handling_no, pile_id, station_id, batch_id, item_id, bike_id,
                            fault_level, fault_desc, status, handler_name, handle_note, handled_at,
                            rechecker_name, recheck_pass, recheck_note, rechecked_at, rev,
                            created_at, updated_at) VALUES
(1, 'FH20260920001', 103, 1, 1, 3, NULL,
    'HIGH', '桩锁卡死无法开合，空桩立即隔离', 'FH_PENDING', NULL, NULL, NULL,
    NULL, NULL, NULL, NULL, 0,
    '2026-09-20 10:05:00', '2026-09-20 10:05:00'),
(2, 'FH20260920002', 101, 1, 1, 1, 1001,
    'HIGH', '供电模块无输出，桩锁无法正常取电', 'FH_HANDLED', '孙维修', '更换供电模块并复测取电正常', '2026-09-23 14:20:00',
    NULL, NULL, NULL, NULL, 1,
    '2026-09-20 09:15:00', '2026-09-23 14:20:00'),
(3, 'FH20260926003', 104, 1, 3, 8, 1003,
    'HIGH', '桩锁无法落锁，桩上仍有车，先待隔离，禁止新车进入', 'FH_PENDING', NULL, NULL, NULL,
    NULL, NULL, NULL, NULL, 0,
    '2026-09-26 09:30:00', '2026-09-26 09:30:00'),
(4, 'FH20260921004', 107, 2, 2, 6, 1004,
    'MID', '通信模块离线，平台读不到桩状态', 'FH_RECHECK_PASS', '李工', '更换 4G 通信模组，信号恢复', '2026-09-24 10:30:00',
    '钱运营', 1, '通信在线率 100%，按真实占车恢复为占用', '2026-09-25 16:20:00', 3,
    '2026-09-21 09:40:00', '2026-09-25 16:20:00');
