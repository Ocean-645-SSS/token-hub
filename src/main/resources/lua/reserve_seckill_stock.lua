local activity = KEYS[1]
local stockKey = KEYS[2]
local usersKey = KEYS[3]
local reservationKey = KEYS[4]
local userId = ARGV[1]
local requestId = ARGV[2]
local now = tonumber(ARGV[3])

if redis.call('EXISTS', activity) == 0 or redis.call('EXISTS', stockKey) == 0 then
    return 6
end
if redis.call('HGET', activity, 'status') ~= '1' then
    return 3
end
if now < tonumber(redis.call('HGET', activity, 'startTime')) then
    return 1
end
if now > tonumber(redis.call('HGET', activity, 'endTime')) then
    return 2
end
if redis.call('SISMEMBER', usersKey, userId) == 1 then
    return 5
end
if tonumber(redis.call('GET', stockKey)) <= 0 then
    return 4
end

redis.call('DECR', stockKey)
redis.call('SADD', usersKey, userId)
redis.call('HSET', reservationKey, 'status', 'PENDING', 'requestId', requestId, 'userId', userId, 'activityId', redis.call('HGET', activity, 'id'), 'packageId', redis.call('HGET', activity, 'packageId'))
return 0
