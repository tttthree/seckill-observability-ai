-- 原子删除死信并重投：HELD 保留原预占；LEGACY 重新预占。
-- KEYS: deadLetterStream, targetStream, stockKey, orderedUsersKey, recoveryPendingSet
-- ARGV: deadLetterMessageId, userId, voucherId, orderId, reservationState（HELD/LEGACY，缺失视为历史）
-- HELD 已预占；历史无标记条目仍需重新预占。未知状态 fail closed。
local held = ARGV[5] == 'HELD'
if ARGV[5] ~= nil and ARGV[5] ~= 'LEGACY' and not held then return -3 end
if not held then
    local stock = tonumber(redis.call('get', KEYS[3]))
    if stock == nil or stock <= 0 then
        return -1
    end
    if redis.call('sismember', KEYS[4], ARGV[2]) == 1 then
        return -2
    end
end

-- 同一 Lua 内确认条目仍在，再先标记恢复；避免重复重放制造悬空 marker。
local entry = redis.call('xrange', KEYS[1], ARGV[1], ARGV[1], 'COUNT', 1)
if #entry == 0 then return 0 end
redis.call('sadd', KEYS[5], ARGV[4])
local removed = redis.call('xdel', KEYS[1], ARGV[1])
if removed == 0 then
    return 0
end
if not held then
    redis.call('incrby', KEYS[3], -1)
    redis.call('sadd', KEYS[4], ARGV[2])
end
redis.call('xadd', KEYS[2], '*',
        'userId', ARGV[2],
        'voucherId', ARGV[3],
        'id', ARGV[4])
return 1
