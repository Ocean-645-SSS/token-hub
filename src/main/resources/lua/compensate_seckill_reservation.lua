local reservationKey = KEYS[1]
local stockKey = KEYS[2]
local releaseKey = KEYS[3]

if redis.call('HGET', reservationKey, 'status') ~= 'PENDING' then
    return 0
end
if redis.call('SETNX', releaseKey, '1') == 0 then
    return 0
end

redis.call('INCR', stockKey)
redis.call('HSET', reservationKey, 'status', 'FAILED')
return 1
