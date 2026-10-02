local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_period_ms = tonumber(ARGV[3])
local cost = tonumber(ARGV[4])

local redis_time = redis.call('TIME')
local now_ms = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)

local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
local last_refill_ms = tonumber(redis.call('HGET', KEYS[1], 'last_refill_ms'))

if tokens == nil or last_refill_ms == nil then
    tokens = capacity
    last_refill_ms = now_ms
else
    local elapsed_ms = math.max(0, now_ms - last_refill_ms)
    tokens = math.min(capacity, tokens + elapsed_ms * refill_tokens / refill_period_ms)
end

local allowed = 0
local retry_after_ms = 0

if tokens >= cost then
    allowed = 1
    tokens = tokens - cost
else
    retry_after_ms = math.max(1, math.ceil((cost - tokens) * refill_period_ms / refill_tokens))
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'last_refill_ms', tostring(now_ms))
redis.call('PEXPIRE', KEYS[1], math.max(1, math.ceil(capacity * refill_period_ms / refill_tokens)))

return tostring(allowed) .. ':' .. tostring(math.floor(tokens)) .. ':' .. tostring(retry_after_ms) .. ':' .. tostring(capacity)