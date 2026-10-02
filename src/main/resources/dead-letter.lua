-- 原子隔离失败消息：ACK + DLQ；保留库存/用户 reservation，避免晚提交竞态。
-- KEYS: sourceStream, stockKey（兼容保留、不修改）, orderedUsersKey（不修改）, deadLetterStream, retryKey
-- ARGV: group 消费者组, messageId 消息 id, userId 用户 id, voucherId 券 id, orderId 订单 id, failureReason 失败原因
local acked = redis.call('xack', KEYS[1], ARGV[1], ARGV[2])
if acked == 0 then
    return 0
end

redis.call('xadd', KEYS[4], '*',
        'userId', ARGV[3],
        'voucherId', ARGV[4],
        'id', ARGV[5],
        'originalMessageId', ARGV[2],
        'failureReason', ARGV[6], 'reservationState', 'HELD')
redis.call('del', KEYS[5])
return 1
