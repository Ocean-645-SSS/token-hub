local current = redis.call('HGET', KEYS[1], 'status')
if current == 'PENDING' then
    redis.call('HSET', KEYS[1], 'status', 'SENT')
    return 1
end
return 0
