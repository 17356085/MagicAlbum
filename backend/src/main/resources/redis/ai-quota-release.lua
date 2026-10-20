-- ARGV[1] = unique lease UUID
-- Return the ZREM count. No key deletion is performed; the short TTL expires it.
return redis.call('ZREM', KEYS[1], ARGV[1])
