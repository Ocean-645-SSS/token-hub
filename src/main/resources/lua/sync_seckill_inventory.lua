-- Snapshots replace the estimate; replay never increments stock.
local previous = redis.call('GET', KEYS[2])
if previous and tonumber(previous) > tonumber(ARGV[2]) then return 0 end
-- Equal-version replacement also repairs an uncommitted Redis pre-decrement.
-- Durable MySQL admission is mandatory: this estimate can include in-flight requests.
redis.call('SET', KEYS[1], ARGV[1])
redis.call('SET', KEYS[2], ARGV[2])
return 1
