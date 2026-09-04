-- Key 前缀与 RedisConstants 对齐：
--   seckill:stock:  = SECKILL_STOCK_KEY
--   seckill:order:  = SECKILL_ORDER_KEY
--   stream.orders   = STREAM_ORDERS_KEY

--1.参数列表
--1.1.优惠券id
local voucherId = ARGV[1]
--1.2.用户id
local userId = ARGV[2]
--1.2.订单id
local orderId = ARGV[3]

--2.数据key
--2.1.库存key    ..  ->  字符串拼接
local stockKey = 'seckill:stock:' .. voucherId
--2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

--3.脚本业务
--3.1.判断库存是否充足 GET stockKey  Redis 的 get 命令返回的是字符串类型,需要转成number才能跟0比较大小
local stock = tonumber(redis.call('get', stockKey))
if (stock == nil or stock <= 0) then
    --3.1.1.库存不足，返回1
    return 1
end
--3.2.判断用户是否下单 SISMEMBER orderKey userId
if (redis.call('sismember', orderKey, userId) == 1) then
    --3.2.1.存在，说明是重复下单，返回2
    return 2
end
--3.3.扣库存 INCRBY stockKey -1
redis.call('incrby', stockKey, -1)
--3.4.下单（保存用户） SADD orderKey userId
redis.call('sadd', orderKey, userId)
--3.5.发送消息到队列。主订单流不能按固定长度裁剪，否则积压时可能删除尚未消费的订单。
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
