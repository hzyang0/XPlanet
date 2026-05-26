-- wrk -t8 -c200 -d30s -s benchmark/article_detail.lua http://localhost:8081
-- 随机访问 article id 1~10,模拟真实热点分布
math.randomseed(os.time())

request = function()
    local id = math.random(1, 10)
    return wrk.format("GET", "/api/article/" .. id)
end

response = function(status, headers, body)
    if status ~= 200 then
        print("non-200: " .. status)
    end
end
