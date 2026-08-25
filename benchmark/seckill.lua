-- wrk -t4 -c100 -d20s -s benchmark/seckill.lua http://localhost:8080
-- Replace the token with a freshly issued demo token. Reusing one user intentionally exercises one-user-one-order.
wrk.method = "POST"
wrk.path = "/api/seckill/activities/1/orders"
wrk.headers["Authorization"] = "Bearer REPLACE_ME"
wrk.headers["Content-Type"] = "application/json"
