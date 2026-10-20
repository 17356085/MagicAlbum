-- ARGV[1] = max concurrent leases
-- ARGV[2] = lease TTL in milliseconds
-- ARGV[3] = unique lease UUID
-- Return 1 when a lease is acquired, otherwise 0.
local clock = redis.call('TIME')
local nowMillis = (tonumber(clock[1]) * 1000) + math.floor(tonumber(clock[2]) / 1000)
local ttlMillis = tonumber(ARGV[2])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMillis)
local current = redis.call('ZCARD', KEYS[1])
if current >= tonumber(ARGV[1]) then
    redis.call('PEXPIRE', KEYS[1], ttlMillis)
    return 0
end

redis.call('ZADD', KEYS[1], nowMillis + ttlMillis, ARGV[3])
redis.call('PEXPIRE', KEYS[1], ttlMillis)
return 1
