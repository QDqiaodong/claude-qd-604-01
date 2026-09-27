-- ============================================================================
-- 测试专用：H2（MySQL 兼容模式）建表脚本。与 mysql/schema.sql 业务结构等价，
-- 但去掉 MySQL 专有的 inline KEY / COMMENT / ENGINE 写法，改用 H2 支持的语法。
-- ============================================================================

CREATE TABLE bike_user (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    phone      VARCHAR(20)  NOT NULL,
    nickname   VARCHAR(50)  NOT NULL,
    balance    DECIMAL(10, 2) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_phone UNIQUE (phone)
);

CREATE TABLE station (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(64)  NOT NULL,
    district   VARCHAR(32)  NOT NULL,
    capacity   INT          NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    longitude  DECIMAL(10, 6),
    latitude   DECIMAL(10, 6),
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_station_code UNIQUE (code)
);

CREATE TABLE pile (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    station_id BIGINT      NOT NULL,
    pile_no    VARCHAR(16) NOT NULL,
    status     VARCHAR(24) NOT NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pile_station_no UNIQUE (station_id, pile_no)
);
CREATE INDEX idx_pile_station ON pile (station_id);

CREATE TABLE bike (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32) NOT NULL,
    model        VARCHAR(32) NOT NULL,
    status       VARCHAR(16) NOT NULL,
    station_id   BIGINT,
    pile_id      BIGINT,
    total_rides  INT         NOT NULL,
    scrap_reason VARCHAR(128),
    created_at   DATETIME    NOT NULL,
    updated_at   DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT uk_bike_code UNIQUE (code),
    CONSTRAINT uk_bike_pile UNIQUE (pile_id)
);
CREATE INDEX idx_bike_station ON bike (station_id);
CREATE INDEX idx_bike_status ON bike (status);

CREATE TABLE rental (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    order_no         VARCHAR(32) NOT NULL,
    user_id          BIGINT      NOT NULL,
    bike_id          BIGINT      NOT NULL,
    start_station_id BIGINT      NOT NULL,
    start_pile_id    BIGINT      NOT NULL,
    end_station_id   BIGINT,
    end_pile_id      BIGINT,
    start_time       DATETIME    NOT NULL,
    end_time         DATETIME,
    duration_minutes INT,
    amount           DECIMAL(10, 2),
    status           VARCHAR(16) NOT NULL,
    created_at       DATETIME    NOT NULL,
    active_bike_id   BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'RIDING' THEN bike_id ELSE NULL END),
    PRIMARY KEY (id),
    CONSTRAINT uk_rental_order_no UNIQUE (order_no),
    CONSTRAINT uk_rental_active_bike UNIQUE (active_bike_id)
);
CREATE INDEX idx_rental_bike ON rental (bike_id);
CREATE INDEX idx_rental_user ON rental (user_id);

CREATE TABLE worker (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    name       VARCHAR(32) NOT NULL,
    phone      VARCHAR(20) NOT NULL,
    skill      VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    station_id BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_worker_phone UNIQUE (phone)
);
CREATE INDEX idx_worker_station ON worker (station_id);

CREATE TABLE work_order (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32) NOT NULL,
    bike_id     BIGINT      NOT NULL,
    fault_type  VARCHAR(32) NOT NULL,
    level       VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    worker_id   BIGINT,
    plan_start  DATETIME,
    plan_end    DATETIME,
    report_time DATETIME    NOT NULL,
    assign_time DATETIME,
    fix_time    DATETIME,
    fix_note    VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT uk_order_no UNIQUE (order_no)
);
CREATE INDEX idx_order_bike ON work_order (bike_id);
CREATE INDEX idx_order_worker_plan ON work_order (worker_id, plan_start, plan_end);
CREATE INDEX idx_order_status ON work_order (status);

CREATE TABLE clearance_order (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    order_no            VARCHAR(32)  NOT NULL,
    source_station_id   BIGINT       NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    total_count         INT          NOT NULL,
    planned_count       INT          NOT NULL,
    migrated_count      INT          NOT NULL,
    blocked_count       INT          NOT NULL,
    plan_remark         VARCHAR(255),
    executing_token     VARCHAR(64),
    executing_at        DATETIME,
    last_execute_time   DATETIME,
    last_execute_result VARCHAR(255),
    cancelled_time      DATETIME,
    completed_time      DATETIME,
    created_at          DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_clearance_order_no UNIQUE (order_no)
);
CREATE INDEX idx_clearance_source ON clearance_order (source_station_id);
CREATE INDEX idx_clearance_status ON clearance_order (status);

CREATE TABLE clearance_receiver (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    clearance_id BIGINT      NOT NULL,
    station_id   BIGINT      NOT NULL,
    seq          INT         NOT NULL,
    created_at   DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_clearance_receiver UNIQUE (clearance_id, station_id)
);
CREATE INDEX idx_receiver_clearance ON clearance_receiver (clearance_id);

CREATE TABLE clearance_item (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    clearance_id       BIGINT       NOT NULL,
    bike_id            BIGINT       NOT NULL,
    bike_kind          VARCHAR(16)  NOT NULL,
    item_status        VARCHAR(16)  NOT NULL,
    plan_station_id    BIGINT,
    plan_pile_id       BIGINT,
    actual_station_id  BIGINT,
    actual_pile_id     BIGINT,
    fail_reason        VARCHAR(255),
    migrated_at        DATETIME,
    created_at         DATETIME     NOT NULL,
    updated_at         DATETIME,
    PRIMARY KEY (id),
    CONSTRAINT uk_clearance_bike UNIQUE (clearance_id, bike_id)
);
CREATE INDEX idx_clearance_item_status ON clearance_item (clearance_id, item_status);
CREATE INDEX idx_clearance_item_bike ON clearance_item (bike_id);

CREATE TABLE operator (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(32)  NOT NULL,
    phone      VARCHAR(20)  NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    created_at DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_operator_phone UNIQUE (phone)
);

CREATE TABLE inspection_batch (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    batch_no                  VARCHAR(32)  NOT NULL,
    station_id                BIGINT       NOT NULL,
    status                    VARCHAR(16)  NOT NULL,
    creator_id                BIGINT       NOT NULL,
    remark                    VARCHAR(255),
    pile_count                INT          NOT NULL,
    snapshot_occupied_count   INT          NOT NULL,
    snapshot_idle_count       INT          NOT NULL,
    snapshot_disabled_count   INT          NOT NULL,
    checked_count             INT          NOT NULL,
    normal_count              INT          NOT NULL,
    fault_count               INT          NOT NULL,
    pending_quarantine_count  INT          NOT NULL,
    quarantined_count         INT          NOT NULL,
    pending_recheck_count     INT          NOT NULL,
    finished_at               DATETIME,
    created_at                DATETIME     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_inspection_batch_no UNIQUE (batch_no)
);
CREATE INDEX idx_inspection_batch_station ON inspection_batch (station_id);
CREATE INDEX idx_inspection_batch_status ON inspection_batch (status);

CREATE TABLE inspection_item (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    batch_id              BIGINT       NOT NULL,
    pile_id               BIGINT       NOT NULL,
    pile_no               VARCHAR(16)  NOT NULL,
    snapshot_bike_code    VARCHAR(32),
    item_result           VARCHAR(16)  NOT NULL,
    lock_result           VARCHAR(16),
    power_result          VARCHAR(16),
    com_result            VARCHAR(16),
    fault_level           VARCHAR(16),
    fault_desc            VARCHAR(255),
    dispose_status        VARCHAR(16)  NOT NULL,
    inspector_id          BIGINT,
    inspected_at          DATETIME,
    inspect_occupy_note   VARCHAR(255),
    disposer_id           BIGINT,
    dispose_started_at    DATETIME,
    dispose_note          VARCHAR(255),
    disposed_at           DATETIME,
    rechecker_id          BIGINT,
    rechecked_at          DATETIME,
    recheck_result        VARCHAR(16),
    recheck_note          VARCHAR(255),
    recheck_pass_count    INT          NOT NULL DEFAULT 0,
    recheck_fail_count    INT          NOT NULL DEFAULT 0,
    created_at            DATETIME     NOT NULL,
    updated_at           DATETIME,
    active_pile_id        BIGINT GENERATED ALWAYS AS
                          (CASE WHEN dispose_status IN ('PROCESSING', 'FIXED', 'RECHECK_FAIL') THEN pile_id ELSE NULL END),
    PRIMARY KEY (id),
    CONSTRAINT uk_inspection_item UNIQUE (batch_id, pile_id),
    CONSTRAINT uk_inspection_active_pile UNIQUE (active_pile_id)
);
CREATE INDEX idx_inspection_item_batch ON inspection_item (batch_id, item_result);
CREATE INDEX idx_inspection_item_pile ON inspection_item (pile_id);
CREATE INDEX idx_inspection_item_dispose ON inspection_item (dispose_status);

CREATE TABLE inspection_verify_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    item_id       BIGINT       NOT NULL,
    batch_id      BIGINT       NOT NULL,
    pile_id       BIGINT       NOT NULL,
    result        VARCHAR(16)  NOT NULL,
    note          VARCHAR(255),
    operator_id   BIGINT       NOT NULL,
    pile_restored VARCHAR(24),
    restored_bike_code VARCHAR(32),
    created_at    DATETIME     NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_verify_log_item ON inspection_verify_log (item_id);
CREATE INDEX idx_verify_log_batch ON inspection_verify_log (batch_id);
