-- 原子性获取给定key，若key存在返回其值，若key不存在则设置key并返回nil
-- 兼容 Redis 6.x：不使用 SET NX GET 组合（Redis 7.0+ 才支持），改用 GET + 条件 SET
-- Redis 执行 Lua 脚本时是原子的，脚本运行期间不会插入其他命令，因此下面的读写不存在并发问题
local key = KEYS[1]
local value = ARGV[1]
local expire_time_ms = ARGV[2]

local oldValue = redis.call('GET', key)
if oldValue then
    return oldValue
end

redis.call('SET', key, value, 'PX', expire_time_ms)
return nil
