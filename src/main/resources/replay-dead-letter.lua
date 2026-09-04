-- 原子地恢复 Redis 预占：移除一条死信记录，并重新投递到主流。
-- KEYS: deadLetterStream 死信流, targetStream 目标流, stockKey 库存 key, orderedUsersKey 已下单用户 key
-- ARGV: deadLetterMessageId 死信消息 id, userId 用户 id, voucherId 券 id, orderId 订单 id
local stock = tonumber(redis.call('get', KEYS[3]))
if stock == nil or stock <= 0 then
    return -1
end
if redis.call('sismember', KEYS[4], ARGV[2]) == 1 then
    return -2
end

local removed = redis.call('xdel', KEYS[1], ARGV[1])
if removed == 0 then
    return 0
end
redis.call('incrby', KEYS[3], -1)
redis.call('sadd', KEYS[4], ARGV[2])
redis.call('xadd', KEYS[2], '*',
        'userId', ARGV[2],
        'voucherId', ARGV[3],
        'id', ARGV[4])
return 1
