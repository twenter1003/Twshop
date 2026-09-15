-- status 컬럼을 MySQL 네이티브 ENUM에서 VARCHAR(20)으로 전환한다.
-- 결정 배경은 docs/decisions.md의 "[2026-09-15] status 컬럼 타입" 항목 참고: 이 DB를
-- 쓰는 소비자가 이 애플리케이션 하나뿐이라 ENUM이 주는 DB 레벨 방어의 실익이 낮고, 반대로
-- 상태값 추가 시 코드(Kotlin enum)와 DB(ALTER TABLE MODIFY COLUMN)를 이중으로 관리해야
-- 하는 동기화 리스크가 `ddl-auto: validate`로도 잡히지 않는다는 걸 QA가 실험으로 확인했다.
--
-- 컬럼 순서/NOT NULL/기본값 없음은 기존 ENUM 컬럼과 동일하게 유지한다 — 값 자체(문자열)는
-- 바뀌지 않으므로 데이터 마이그레이션(UPDATE)은 필요 없다.
ALTER TABLE inventory_unit
    MODIFY COLUMN status VARCHAR(20) NOT NULL;

ALTER TABLE purchase_attempt
    MODIFY COLUMN status VARCHAR(20) NOT NULL;
