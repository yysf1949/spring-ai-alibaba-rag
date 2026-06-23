-- Redis sliding-window rate limiter (Phase 34 R6 / T3).
-- KEYS[1] = bucket key, e.g. "ratelimit:tenant:dev"
-- ARGV[1] = current time in ms (server-supplied so all cluster nodes
--           share one clock; the script itself never calls TIME)
-- ARGV[2] = window length in ms
-- ARGV[3] = max requests per window
-- ARGV[4] = unique request id (the caller-supplied one, OR a server-
--           generated UUID when the caller didn't supply one)
--
-- Returns: { allowed, current_count, reset_ms }
--   allowed       = 1 if the request is permitted, 0 if rate-limited
--   current_count = number of requests in the window AFTER this call
--   reset_ms      = how many ms until the oldest entry in the window
--                   expires (so the caller can populate Retry-After)
--
-- Algorithm: ZADD on a sorted set keyed by ms. ZREMRANGEBYSCORE drops
-- entries older than (now - window). ZCARD reads the current count. We
-- always ZADD first and then decide — if we ZADD-then-check, the count
-- we report includes this call, which is what callers want (the
-- "current_count" is the snapshot AFTER acceptance). For the rate-limit
-- decision we check BEFORE adding, so a request that would exceed the
-- limit is rejected without contributing to the count.

local key       = KEYS[1]
local now       = tonumber(ARGV[1])
local windowMs  = tonumber(ARGV[2])
local maxReqs   = tonumber(ARGV[3])
local requestId = ARGV[4]

if not now or not windowMs or not maxReqs then
    return redis.error_reply("missing required ARGV")
end

-- 1. Drop entries outside the window.
local cutoff = now - windowMs
redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)

-- 2. Count what remains.
local current = redis.call('ZCARD', key)

if current >= maxReqs then
    -- 3a. Denied. Find when the oldest entry will fall out of the
    -- window so the caller can populate Retry-After.
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    local resetMs
    if oldest[2] then
        resetMs = (tonumber(oldest[2]) + windowMs) - now
        if resetMs < 0 then resetMs = 0 end
    else
        resetMs = windowMs
    end
    return { 0, current, resetMs }
end

-- 3b. Allowed. ZADD this request and re-set the TTL so abandoned
-- buckets don't leak (window * 2 gives Redis plenty of time to GC a
-- tenant that goes quiet).
redis.call('ZADD', key, now, requestId)
redis.call('PEXPIRE', key, windowMs * 2)

return { 1, current + 1, 0 }
