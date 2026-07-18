-- 参数列表, 优惠券id，用户id,订单id
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
--数据key， 库存key，订单key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
-- 脚本业务
-- 判断库存是否充足，不足返回1
if(tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
-- 判断用户是否下单
if(redis.call('sismember', orderKey, userId) == 1) then
-- 重复下单则返回2
    return 2
end
-- 不重复则扣库存，处理下单
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
-- 发送消息到消息队列中
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId',voucherId, 'id', orderId)
return 0