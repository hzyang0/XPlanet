-- 用法:
-- 1. 先登录拿 token:
--    curl -s -X POST -H "Content-Type: application/json" -d '{"username":"alice","password":"password"}' \
--      http://172.23.176.1:8083/api/user/login
-- 2. 复制返回的 token,填到下面 TOKEN
-- 3. 运行: wrk -t8 -c500 -d30s -s like-v2.lua http://172.23.176.1:8082

local TOKEN = "replace-with-jwt-from-login"

wrk.method = "POST"
wrk.headers["Authorization"] = "Bearer " .. TOKEN

request = function()
    return wrk.format("POST", "/api/like/1")
end
