-- Flyway 도입 전 Hibernate `ddl-auto: create-drop`이 만들어주던 스키마를 그대로 옮긴 baseline이다.
-- 손으로 짐작해서 쓰지 않고, 실제 MySQL 8 위에서 `ddl-auto: create`로 엔티티를 한 번 생성시킨
-- 뒤 `SHOW CREATE TABLE`로 나온 결과를 그대로 옮겼다 (2026-09-15, Flyway 도입 작업).
--
-- 확인해 둘 만한 지점:
-- - status 컬럼: `@Enumerated(EnumType.STRING)` + `length = 20`으로 선언했지만, Hibernate의
--   MySQL 방언은 자바 enum을 VARCHAR가 아니라 MySQL 네이티브 `ENUM(...)` 컬럼으로 매핑한다.
--   길이 힌트는 무시된다 — 짐작이 아니라 실제 생성 결과를 확인하고서야 알았다.
-- - inventory_unit.version: `@Version`은 `@Column` 옵션을 따로 주지 않았지만 NOT NULL로
--   생성된다 (Hibernate가 버전 컬럼은 기본값 0으로 항상 채워진다고 가정하기 때문).
-- - 문자셋/콜레이션은 MySQL 8 기본값(utf8mb4 / utf8mb4_0900_ai_ci)을 그대로 명시했다.
-- - FK/UNIQUE 제약조건 이름은 Hibernate가 자동 생성한 해시값(FK9pqcxsfpc3...) 대신 사람이
--   읽을 수 있는 이름으로 바꿨다. `ddl-auto: validate`는 테이블/컬럼 존재 여부와 타입만
--   검증하고 제약조건/인덱스 이름까지는 비교하지 않으므로 이름을 바꿔도 검증에 영향이 없다.

CREATE TABLE product (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    price DECIMAL(12, 2) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE inventory_unit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    unit_code VARCHAR(255) NOT NULL,
    status ENUM('AVAILABLE', 'CANCELLED', 'CONFIRMED', 'RESERVED') NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_unit_unit_code (unit_code),
    KEY idx_inventory_unit_product_id (product_id),
    CONSTRAINT fk_inventory_unit_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE purchase_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    buyer_id VARCHAR(255) NOT NULL,
    inventory_unit_id BIGINT DEFAULT NULL,
    status ENUM('CANCELLED', 'CONFIRMED', 'FAILED', 'RESERVED') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_purchase_attempt_product_id (product_id),
    KEY idx_purchase_attempt_inventory_unit_id (inventory_unit_id),
    CONSTRAINT fk_purchase_attempt_product FOREIGN KEY (product_id) REFERENCES product (id),
    CONSTRAINT fk_purchase_attempt_inventory_unit FOREIGN KEY (inventory_unit_id) REFERENCES inventory_unit (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
