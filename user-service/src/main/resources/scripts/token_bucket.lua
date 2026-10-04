-- Atomic token-bucket rate limiter.
--
-- KEYS[1]  bucket key (hash with fields: tokens, ts)
-- ARGV[1]  capacity          (max tokens / burst size)
-- ARGV[2]  refill tokens     (tokens added per refill period)
-- ARGV[3]  refill period ms
-- ARGV[4]  cost              (tokens this request consumes; 0 = check only, at least 1 token must remain)
--
-- Returns { allowed (1|0), remaining tokens (floored), retry-after ms }
--
-- Uses Redis server time so app instances with clock skew still share one consistent bucket.

local capacity      = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local period_ms     = tonumber(ARGV[3])
local cost          = tonumber(ARGV[4])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local rate = refill_tokens / period_ms -- tokens per ms

local state  = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts     = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)

local required = math.max(cost, 1)
local allowed  = 0
local retry_ms = 0
if tokens >= required then
  tokens  = tokens - cost
  allowed = 1
else
  retry_ms = math.ceil((required - tokens) / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- Expire once the bucket would be full again; an absent key is equivalent to a full bucket.
redis.call('PEXPIRE', KEYS[1], math.max(1, math.ceil((capacity - tokens) / rate)))

return { allowed, math.floor(tokens), retry_ms }
