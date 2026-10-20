local userId = redis.call('HGET', KEYS[1], 'userId')
if not userId then return '__MISSING__' end
local expiresAt = tonumber(redis.call('HGET', KEYS[1], 'expiresAt') or '0')
if expiresAt <= tonumber(ARGV[1]) then
  redis.call('DEL', KEYS[1])
  return '__EXPIRED__'
end
local challenge = redis.call('HGET', KEYS[1], 'codeChallenge')
if not challenge or challenge ~= ARGV[2] then return '__BINDING__' end
redis.call('DEL', KEYS[1])
return userId
