-- wrk -t8 -c500 -d30s -s benchmark/like.lua http://localhost:8082
-- 模拟大量不同用户点赞同一篇热门文章
math.randomseed(os.time())
counter = 0

request = function()
    counter = counter + 1
    local uid = math.random(1, 100000)
    wrk.method = "POST"
    wrk.headers["X-User-Id"] = tostring(uid)
    return wrk.format("POST", "/api/like/1")
end
