local releaseKey = KEYS[1]
local stockKey = KEYS[2]
if redis.call('SETNX', releaseKey, '1') == 1 then redis.call('INCR', stockKey); return 1 end
return 0
