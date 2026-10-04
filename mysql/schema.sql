SET NAMES utf8mb4;

DROP TABLE IF EXISTS fault_takeover_events;
DROP TABLE IF EXISTS fault_takeover_items;
DROP TABLE IF EXISTS fault_takeover_plans;
DROP TABLE IF EXISTS driver_shifts;
DROP TABLE IF EXISTS gate_transactions;
DROP TABLE IF EXISTS work_instructions;
DROP TABLE IF EXISTS containers;
DROP TABLE IF EXISTS yard_slots;
DROP TABLE IF EXISTS yard_zones;
DROP TABLE IF EXISTS vessel_schedules;
DROP TABLE IF EXISTS cranes;
DROP TABLE IF EXISTS berths;

CREATE TABLE berths (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    berth_code VARCHAR(20) NOT NULL UNIQUE,
    shoreline_length DECIMAL(8,2) NOT NULL,
    water_depth DECIMAL(5,2) NOT NULL,
    capacity_teu INT NOT NULL,
    status VARCHAR(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vessel_schedules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    berth_id BIGINT NOT NULL,
    vessel_name VARCHAR(80) NOT NULL,
    voyage_no VARCHAR(40) NOT NULL,
    shipping_company VARCHAR(40) NOT NULL,
    expected_arrival DATETIME NOT NULL,
    expected_departure DATETIME NOT NULL,
    handling_teu INT NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    assigned_cranes VARCHAR(100),
    status VARCHAR(20) NOT NULL,
    INDEX idx_schedule_berth (berth_id),
    CONSTRAINT fk_schedule_berth FOREIGN KEY (berth_id) REFERENCES berths(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cranes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(20) NOT NULL UNIQUE,
    crane_type VARCHAR(20) NOT NULL,
    rated_load DECIMAL(6,2) NOT NULL,
    lifting_height DECIMAL(6,2),
    trolley_speed DECIMAL(6,2),
    span DECIMAL(6,2),
    stack_layers INT,
    yard_zone VARCHAR(10),
    rated_efficiency INT NOT NULL,
    status VARCHAR(20) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE driver_shifts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    crane_id BIGINT NOT NULL,
    driver_name VARCHAR(40) NOT NULL,
    shift_start DATETIME NOT NULL,
    shift_end DATETIME NOT NULL,
    handled_boxes INT NOT NULL,
    work_hours DECIMAL(5,2) NOT NULL,
    INDEX idx_shift_crane_time (crane_id, shift_start),
    CONSTRAINT fk_shift_crane FOREIGN KEY (crane_id) REFERENCES cranes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE yard_zones (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    zone_code VARCHAR(10) NOT NULL UNIQUE,
    bay_count INT NOT NULL,
    row_count INT NOT NULL,
    tier_count INT NOT NULL,
    container_type_limit VARCHAR(100) NOT NULL,
    power_outlet TINYINT(1) NOT NULL,
    zone_type VARCHAR(30) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE yard_slots (
    yard_zone VARCHAR(10) NOT NULL,
    bay INT NOT NULL,
    slot_row INT NOT NULL,
    tier INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    refrigerated TINYINT(1) NOT NULL,
    occupied_container_no VARCHAR(20),
    reservation_no VARCHAR(40),
    PRIMARY KEY (yard_zone, bay, slot_row, tier),
    INDEX idx_slot_container (occupied_container_no),
    INDEX idx_slot_status (yard_zone, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE containers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    container_no VARCHAR(20) NOT NULL UNIQUE,
    type VARCHAR(20) NOT NULL,
    load_status VARCHAR(20) NOT NULL,
    owner VARCHAR(50) NOT NULL,
    shipping_company VARCHAR(50) NOT NULL,
    weight DECIMAL(7,2) NOT NULL,
    dangerous TINYINT(1) NOT NULL,
    temperature_requirement VARCHAR(40),
    current_yard_zone VARCHAR(10),
    current_bay INT,
    current_row INT,
    current_tier INT,
    entry_time DATETIME,
    exit_time DATETIME,
    pickup_status VARCHAR(20) NOT NULL,
    paid_inspection TINYINT(1) NOT NULL,
    INDEX idx_container_position (current_yard_zone, current_bay),
    INDEX idx_container_company (shipping_company)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE work_instructions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    instruction_no VARCHAR(30) NOT NULL UNIQUE,
    container_no VARCHAR(20) NOT NULL,
    from_yard_zone VARCHAR(10),
    from_bay INT,
    from_row INT,
    from_tier INT,
    to_yard_zone VARCHAR(10),
    to_bay INT,
    to_row INT,
    to_tier INT,
    crane_code VARCHAR(20),
    instruction_type VARCHAR(20) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    work_stage VARCHAR(20) NOT NULL DEFAULT 'AWAITING_START',
    confirmed_stage VARCHAR(20) NOT NULL DEFAULT 'AWAITING_START',
    version INT NOT NULL DEFAULT 0,
    vessel_schedule_id BIGINT,
    created_at DATETIME NOT NULL,
    completed_at DATETIME,
    INDEX idx_instruction_container (container_no),
    INDEX idx_instruction_crane (crane_code, status),
    INDEX idx_instruction_schedule (vessel_schedule_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fault_takeover_plans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_no VARCHAR(40) NOT NULL UNIQUE,
    crane_id BIGINT NOT NULL,
    crane_code VARCHAR(20) NOT NULL,
    crane_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    open_key VARCHAR(20) UNIQUE,
    created_by VARCHAR(40),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    closed_at DATETIME,
    INDEX idx_plan_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fault_takeover_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    instruction_id BIGINT NOT NULL,
    instruction_no VARCHAR(30) NOT NULL,
    container_no VARCHAR(20) NOT NULL,
    status_snapshot VARCHAR(20) NOT NULL,
    stage_snapshot VARCHAR(20) NOT NULL,
    confirmed_stage_snapshot VARCHAR(20) NOT NULL,
    decision VARCHAR(20) NOT NULL,
    takeover_crane_code VARCHAR(20),
    failure_reason VARCHAR(300),
    decided_by VARCHAR(40),
    decided_at DATETIME,
    INDEX idx_item_plan (plan_id),
    INDEX idx_item_instruction (instruction_id),
    CONSTRAINT fk_item_plan FOREIGN KEY (plan_id) REFERENCES fault_takeover_plans(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fault_takeover_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_id BIGINT NOT NULL,
    item_id BIGINT,
    instruction_id BIGINT,
    event_type VARCHAR(30) NOT NULL,
    actor VARCHAR(40),
    detail VARCHAR(500),
    created_at DATETIME NOT NULL,
    INDEX idx_event_plan (plan_id),
    CONSTRAINT fk_event_plan FOREIGN KEY (plan_id) REFERENCES fault_takeover_plans(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE gate_transactions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    lane_no VARCHAR(20) NOT NULL,
    direction VARCHAR(10) NOT NULL,
    plate_no VARCHAR(20) NOT NULL,
    container_no VARCHAR(20) NOT NULL,
    appointment_no VARCHAR(30) NOT NULL,
    passing_time DATETIME NOT NULL,
    inspection_result VARCHAR(30) NOT NULL,
    congestion VARCHAR(20) NOT NULL,
    INDEX idx_gate_container (container_no),
    INDEX idx_gate_time (passing_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO berths (berth_code, shoreline_length, water_depth, capacity_teu, status) VALUES
('B01', 320.00, 15.80, 12000, 'BERTHED'),
('B02', 280.00, 14.60, 9000, 'BERTHED'),
('B03', 210.00, 12.50, 5000, 'IDLE'),
('B04', 160.00, 10.80, 2500, 'MAINTENANCE');

INSERT INTO vessel_schedules (berth_id, vessel_name, voyage_no, shipping_company, expected_arrival, expected_departure, handling_teu, operation_type, assigned_cranes, status) VALUES
(1, '中远海运宇宙', 'COSCO-2618E', '中远海运', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 12 HOUR, 1860, 'BOTH', 'QC01,QC02', 'WORKING'),
(2, '马士基天津', 'MSK-903W', '马士基', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 16 HOUR, 1320, 'UNLOAD', 'QC03', 'BERTHED'),
(3, '长荣明月', 'EMC-118N', '长荣海运', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 7 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 25 HOUR, 780, 'LOAD', NULL, 'EXPECTED'),
(1, '达飞里沃利', 'CMA-622S', '达飞轮船', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 32 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 49 HOUR, 1540, 'BOTH', NULL, 'EXPECTED'),
(2, '地中海伊莎贝拉', 'MSC-509A', '地中海航运', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 51 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 72 HOUR, 2050, 'BOTH', NULL, 'EXPECTED');

INSERT INTO cranes (code, crane_type, rated_load, lifting_height, trolley_speed, span, stack_layers, yard_zone, rated_efficiency, status) VALUES
('QC01', 'QUAY', 65.00, 48.00, 240.00, NULL, NULL, NULL, 32, 'WORKING'),
('QC02', 'QUAY', 70.00, 52.00, 250.00, NULL, NULL, NULL, 35, 'WORKING'),
('QC03', 'QUAY', 65.00, 46.00, 230.00, NULL, NULL, NULL, 30, 'WORKING'),
('QC04', 'QUAY', 60.00, 44.00, 220.00, NULL, NULL, NULL, 28, 'STANDBY'),
('YC01', 'YARD', 41.00, 18.00, 90.00, 23.47, 5, 'A', 24, 'WORKING'),
('YC02', 'YARD', 41.00, 18.00, 90.00, 23.47, 5, 'A', 24, 'STANDBY'),
('YC03', 'YARD', 45.00, 20.00, 95.00, 26.00, 6, 'B', 22, 'WORKING'),
('YC04', 'YARD', 41.00, 18.00, 88.00, 23.47, 5, 'C', 22, 'FAULT'),
('YC05', 'YARD', 41.00, 18.00, 88.00, 23.47, 5, 'C', 22, 'STANDBY');

INSERT INTO driver_shifts (crane_id, driver_name, shift_start, shift_end, handled_boxes, work_hours) VALUES
(1, '陈海峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, 29, 1.0),
(1, '陈海峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, 31, 1.0),
(1, '陈海峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, 28, 1.0),
(1, '陈海峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 33, 1.0),
(2, '周启航', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, 35, 1.0),
(2, '周启航', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, 34, 1.0),
(2, '周启航', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, 37, 1.0),
(2, '周启航', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 32, 1.0),
(3, '蒋东海', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, 26, 1.0),
(3, '蒋东海', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, 28, 1.0),
(3, '蒋东海', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, 30, 1.0),
(4, '孙明', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, 18, 1.0),
(5, '吴强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, 22, 1.0),
(5, '吴强', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 24, 1.0),
(7, '林晓峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, 20, 1.0),
(7, '林晓峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, 23, 1.0);

INSERT INTO yard_zones (zone_code, bay_count, row_count, tier_count, container_type_limit, power_outlet, zone_type) VALUES
('A', 3, 6, 4, '20GP,40GP,40HQ,45HC', 0, '普通重箱区'),
('B', 2, 4, 3, 'REEFER', 1, '冷藏箱区'),
('C', 1, 4, 3, '20GP,40GP,40HQ,45HC', 0, '危险品专用区');

INSERT INTO yard_slots (yard_zone, bay, slot_row, tier, status, refrigerated, occupied_container_no, reservation_no)
SELECT 'A', b.n, r.n, t.n, 'EMPTY', 0, NULL, NULL
FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3) b
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) r
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) t;

INSERT INTO yard_slots (yard_zone, bay, slot_row, tier, status, refrigerated, occupied_container_no, reservation_no)
SELECT 'B', b.n, r.n, t.n, 'EMPTY', 1, NULL, NULL
FROM (SELECT 1 n UNION ALL SELECT 2) b
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) r
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3) t;

INSERT INTO yard_slots (yard_zone, bay, slot_row, tier, status, refrigerated, occupied_container_no, reservation_no)
SELECT 'C', 1, r.n, t.n, 'EMPTY', 0, NULL, NULL
FROM (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) r
CROSS JOIN (SELECT 1 n UNION ALL SELECT 2 UNION ALL SELECT 3) t;

UPDATE yard_slots SET status='RESERVED', reservation_no='RSV-0928-01' WHERE yard_zone='A' AND bay=1 AND slot_row=3 AND tier=2;
UPDATE yard_slots SET status='RESERVED', reservation_no='RSV-0928-02' WHERE yard_zone='A' AND bay=2 AND slot_row=6 AND tier=2;
UPDATE yard_slots SET status='DISABLED' WHERE yard_zone='A' AND bay=1 AND slot_row=6 AND tier=4;
UPDATE yard_slots SET status='DISABLED' WHERE yard_zone='A' AND bay=3 AND slot_row=4 AND tier=4;

INSERT INTO containers (container_no, type, load_status, owner, shipping_company, weight, dangerous, temperature_requirement, current_yard_zone, current_bay, current_row, current_tier, entry_time, exit_time, pickup_status, paid_inspection) VALUES
('MSCU1234567','40HQ','HEAVY','华东电器','地中海航运',27.60,0,NULL,'A',1,1,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 HOUR,NULL,'BLOCKED',0),
('COSU7654321','20GP','HEAVY','苏港机械','中远海运',18.20,0,NULL,'A',1,3,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 HOUR,NULL,'AVAILABLE',1),
('TEMU2048123','40GP','EMPTY','通达租箱','长荣海运',4.10,0,NULL,'A',1,4,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 HOUR,NULL,'AVAILABLE',1),
('CMAU9081726','20GP','HEAVY','江南纺织','达飞轮船',16.80,0,NULL,'A',2,1,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 HOUR,NULL,'BLOCKED',1),
('EISU3509184','20GP','EMPTY','远海物流','长荣海运',2.30,0,NULL,'A',2,2,2,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 16 HOUR,NULL,'AVAILABLE',0),
('OOLU5512098','40HQ','HEAVY','嘉禾食品','中远海运',24.90,0,NULL,'A',2,3,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 HOUR,NULL,'BLOCKED',0),
('MSKU7741200','REEFER','HEAVY','海湾冷链','马士基',25.40,0,'-18℃','B',1,1,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 14 HOUR,NULL,'BLOCKED',1),
('CAXU8801203','REEFER','HEAVY','鲜达水产','达飞轮船',26.10,0,'2℃~6℃','B',1,2,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR,NULL,'AVAILABLE',1),
('ZIMU7213409','20GP','HEAVY','华化材料','地中海航运',21.60,1,NULL,'C',1,1,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 HOUR,NULL,'BLOCKED',1),
('WHLU6673910','40GP','HEAVY','北辰设备','中远海运',28.10,0,NULL,'A',3,2,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 HOUR,NULL,'AVAILABLE',1),
('MEDU4401982','20GP','HEAVY','临港贸易','地中海航运',17.30,0,NULL,'A',3,4,2,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR,NULL,'BLOCKED',0),
('TRHU9055118','40HQ','HEAVY','辰光家居','马士基',25.80,0,NULL,'A',1,1,2,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR,NULL,'BLOCKED',1),
('YMLU6622901','20GP','EMPTY','环亚租箱','长荣海运',2.25,0,NULL,'A',1,6,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR,NULL,'AVAILABLE',1),
('APLU3304912','20GP','HEAVY','申城制造','马士基',19.70,0,NULL,'A',2,5,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR,NULL,'AVAILABLE',1),
('HLCU2285017','40GP','HEAVY','东方汽配','中远海运',26.40,0,NULL,'A',2,1,3,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR,NULL,'BLOCKED',0),
('TGHU1190820','20GP','HEAVY','华运纸业','达飞轮船',18.80,0,NULL,'A',3,5,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR,NULL,'AVAILABLE',1),
('SEGU7781902','20GP','EMPTY','寰球箱运','马士基',2.20,0,NULL,'A',3,6,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR,NULL,'BLOCKED',0),
('FCIU6190034','40HQ','HEAVY','欧亚家电','地中海航运',27.20,0,NULL,'A',3,1,3,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR,NULL,'BLOCKED',1),
('DRYU2205197','20GP','HEAVY','沪港供应链','中远海运',20.10,0,NULL,NULL,NULL,NULL,NULL,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR,NULL,'BLOCKED',1),
('NYKU7732104','REEFER','HEAVY','蓝洋生鲜','马士基',24.70,0,'-20℃','B',2,1,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR,NULL,'AVAILABLE',1),
('CSNU3051221','20GP','HEAVY','临港化工','地中海航运',44.50,1,NULL,'C',1,3,1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR,NULL,'BLOCKED',1);

UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='MSCU1234567' WHERE yard_zone='A' AND bay=1 AND slot_row IN (1,2) AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='COSU7654321' WHERE yard_zone='A' AND bay=1 AND slot_row=3 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='TEMU2048123' WHERE yard_zone='A' AND bay=1 AND slot_row IN (4,5) AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='YMLU6622901' WHERE yard_zone='A' AND bay=1 AND slot_row=6 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='TRHU9055118' WHERE yard_zone='A' AND bay=1 AND slot_row IN (1,2) AND tier=2;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='CMAU9081726' WHERE yard_zone='A' AND bay=2 AND slot_row=1 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='EISU3509184' WHERE yard_zone='A' AND bay=2 AND slot_row=2 AND tier=2;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='OOLU5512098' WHERE yard_zone='A' AND bay=2 AND slot_row IN (3,4) AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='APLU3304912' WHERE yard_zone='A' AND bay=2 AND slot_row=5 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='HLCU2285017' WHERE yard_zone='A' AND bay=2 AND slot_row IN (1,2) AND tier=3;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='WHLU6673910' WHERE yard_zone='A' AND bay=3 AND slot_row IN (2,3) AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='MEDU4401982' WHERE yard_zone='A' AND bay=3 AND slot_row=4 AND tier=2;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='TGHU1190820' WHERE yard_zone='A' AND bay=3 AND slot_row=5 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='SEGU7781902' WHERE yard_zone='A' AND bay=3 AND slot_row=6 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='FCIU6190034' WHERE yard_zone='A' AND bay=3 AND slot_row IN (1,2) AND tier=3;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='MSKU7741200' WHERE yard_zone='B' AND bay=1 AND slot_row=1 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='CAXU8801203' WHERE yard_zone='B' AND bay=1 AND slot_row=2 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='NYKU7732104' WHERE yard_zone='B' AND bay=2 AND slot_row=1 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='ZIMU7213409' WHERE yard_zone='C' AND bay=1 AND slot_row=1 AND tier=1;
UPDATE yard_slots SET status='OCCUPIED', occupied_container_no='CSNU3051221' WHERE yard_zone='C' AND bay=1 AND slot_row=3 AND tier=1;

INSERT INTO work_instructions (instruction_no, container_no, from_yard_zone, from_bay, from_row, from_tier, to_yard_zone, to_bay, to_row, to_tier, crane_code, instruction_type, priority, status, work_stage, confirmed_stage, vessel_schedule_id, created_at, completed_at) VALUES
('WI-20260928-001','MSCU1234567',NULL,NULL,NULL,NULL,'A',1,1,1,'QC01','UNLOAD','VESSEL_CUTOFF','COMPLETED','LANDING','LANDING',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 HOUR,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 27 HOUR),
('WI-20260928-002','COSU7654321',NULL,NULL,NULL,NULL,'A',1,3,1,'QC02','UNLOAD','NORMAL','COMPLETED','LANDING','LANDING',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 HOUR,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 24 HOUR),
('WI-20260928-003','TEMU2048123','A',1,4,1,NULL,NULL,NULL,NULL,'QC02','LOAD','URGENT','PENDING','AWAITING_START','AWAITING_START',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR,NULL),
('WI-20260928-004','CMAU9081726','A',2,1,1,'A',2,3,2,'YC01','SHIFT','NORMAL','PENDING','AWAITING_START','AWAITING_START',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR,NULL),
('WI-20260928-005','MSKU7741200',NULL,NULL,NULL,NULL,'B',1,1,1,'QC03','UNLOAD','URGENT','COMPLETED','LANDING','LANDING',2,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 13 HOUR,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR),
('WI-20260928-006','CAXU8801203','B',1,2,1,NULL,NULL,NULL,NULL,'YC03','PICKUP','NORMAL','PENDING','AWAITING_START','AWAITING_START',2,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR,NULL),
('WI-20260928-007','WHLU6673910',NULL,NULL,NULL,NULL,'A',3,2,1,'QC01','UNLOAD','NORMAL','COMPLETED','LANDING','LANDING',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR),
('WI-20260928-008','ZIMU7213409',NULL,NULL,NULL,NULL,'C',1,1,1,'YC04','UNLOAD','URGENT','COMPLETED','LANDING','LANDING',2,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 HOUR,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR),
('WI-20260928-009','APLU3304912','A',2,5,1,NULL,NULL,NULL,NULL,'YC02','PICKUP','NORMAL','EXECUTING','LIFTING','POSITIONING',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 MINUTE,NULL),
('WI-20260928-010','NYKU7732104',NULL,NULL,NULL,NULL,'B',2,1,1,'QC03','UNLOAD','VESSEL_CUTOFF','COMPLETED','LANDING','LANDING',2,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 MINUTE,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 MINUTE),
('WI-20260928-011','HLCU2285017','A',2,1,3,NULL,NULL,NULL,NULL,'QC02','LOAD','NORMAL','PENDING','AWAITING_START','AWAITING_START',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 MINUTE,NULL),
('WI-20260928-012','CSNU3051221','C',1,3,1,'C',1,4,2,'YC04','SHIFT','NORMAL','PENDING','AWAITING_START','AWAITING_START',NULL,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 MINUTE,NULL),
('WI-20260928-013','ZIMU7213409','C',1,1,1,'C',1,2,2,'YC04','SHIFT','URGENT','EXECUTING','LIFTING','POSITIONING',2,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 MINUTE,NULL),
('WI-20260928-014','TGHU1190820','A',3,5,1,NULL,NULL,NULL,NULL,'QC02','LOAD','NORMAL','EXECUTING','POSITIONING','POSITIONING',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 MINUTE,NULL),
('WI-20260928-015','DRYU2205197',NULL,NULL,NULL,NULL,'A',3,6,2,'QC01','UNLOAD','URGENT','EXECUTING','LIFTING','POSITIONING',1,UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 MINUTE,NULL);

INSERT INTO gate_transactions (lane_no, direction, plate_no, container_no, appointment_no, passing_time, inspection_result, congestion) VALUES
('IN-01','IN','沪B·7K21挂','MSCU1234567','APT-0928-001',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 HOUR,'通过','畅通'),
('IN-02','IN','苏E·52P18','COSU7654321','APT-0928-002',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 HOUR,'通过','畅通'),
('IN-03','IN','浙A·96T33','MSKU7741200','APT-0928-003',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 14 HOUR,'温度复核通过','缓行'),
('IN-04','IN','沪C·3M780','CAXU8801203','APT-0928-004',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR,'通过','畅通'),
('IN-01','IN','皖K·81Q20','ZIMU7213409','APT-0928-005',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 HOUR,'危标核验通过','缓行'),
('OUT-01','OUT','沪D·2H551','TEMU2048123','APT-0928-006',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR,'待放行','拥堵'),
('IN-02','IN','苏B·9C168','APLU3304912','APT-0928-007',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR,'通过','畅通'),
('OUT-02','OUT','浙B·6R901','CAXU8801203','APT-0928-008',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 MINUTE,'查验完成','缓行'),
('IN-03','IN','沪F·5N621','DRYU2205197','APT-0928-009',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR,'等待落位','畅通'),
('OUT-03','OUT','苏F·8L319','APLU3304912','APT-0928-010',UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 MINUTE,'称重复核','拥堵');
