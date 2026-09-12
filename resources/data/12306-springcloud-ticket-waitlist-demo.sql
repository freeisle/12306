-- =============================================================================
-- 场景三「候补购票智能推荐」演示测试数据
-- 目的：把 G35(train_id=1) 全部座位置为已售(seat_status=2)，制造一趟「售罄车次」，
--       用于演示 AI 候补推荐；同时保留 G39(train_id=2) 有票，作为「改乘」对照方案。
--       G35 与 G39 均经停「北京南 → 杭州东」，查询该区间即可同时看到：
--         · G35 各席别 quantity=0 且 candidate=true  → 触发候补成功率估算
--         · G39 各席别 quantity>0                    → 触发「改乘有票」推荐
--
-- 说明：余票数在运行时由 ticket-service 懒加载 COUNT(t_seat WHERE seat_status=0)
--       并缓存进 Redis(index12306-ticket-service:train_station_remaining_ticket:{trainId}_{dep}_{arr})。
--       因此执行本脚本后，必须清除 train_id=1 的余票缓存键，改动才会生效（见文件末尾）。
--
-- 幂等：可重复执行。
-- =============================================================================

USE `12306_ticket`;

-- G35(train_id=1) 全席位售罄
UPDATE `t_seat`
SET `seat_status` = 2
WHERE `train_id` = 1
  AND `seat_status` <> 2;

-- 校验：train_id=1 应无 seat_status=0 的可售座位；train_id=2 仍应有大量可售座位
SELECT `train_id`, `seat_status`, COUNT(*) AS `cnt`
FROM `t_seat`
WHERE `train_id` IN (1, 2)
GROUP BY `train_id`, `seat_status`
ORDER BY `train_id`, `seat_status`;

-- -----------------------------------------------------------------------------
-- 回滚（演示结束后如需恢复 G35 可售）：
--   UPDATE `t_seat` SET `seat_status` = 0 WHERE `train_id` = 1;
-- 无论执行本脚本还是回滚，都需清除 Redis 余票缓存键：
--   KEYS index12306-ticket-service:train_station_remaining_ticket:1_*  → DEL
-- -----------------------------------------------------------------------------
