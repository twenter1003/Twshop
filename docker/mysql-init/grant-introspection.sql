-- PurchaseServiceGapLockConcurrencyTest가 gap lock 증거를 확인하려고 조회하는
-- performance_schema.data_locks / information_schema.innodb_trx는 twshop 계정에
-- 기본 권한이 없다 (2026-09-15 결정, docs/decisions.md 참고). CI에서 부여하는 권한과
-- 동일하게 맞춘다.
GRANT PROCESS ON *.* TO 'twshop'@'%';
GRANT SELECT ON performance_schema.* TO 'twshop'@'%';
FLUSH PRIVILEGES;
