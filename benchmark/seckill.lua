-- Real admission benchmark:
--   $env:SECKILL_TOKENS_FILE = "benchmark/tokens.txt"
--   wrk -t4 -c100 -d20s -s benchmark/seckill.lua http://localhost:8080
-- Tokens must belong to different users. Reusing a single token only measures
-- the one-user-one-order rejection path, not multi-user flash-sale admission.

local token_file = os.getenv("SECKILL_TOKENS_FILE") or "benchmark/tokens.txt"
local file, err = io.open(token_file, "r")
if not file then
  error("cannot open token file: " .. token_file .. ", " .. (err or "unknown error"))
end

local tokens = {}
for line in file:lines() do
  local token = line:match("^%s*(.-)%s*$")
  if token ~= "" then table.insert(tokens, token) end
end
file:close()

if #tokens == 0 then error("token file is empty") end

local sequence = 0
request = function()
  sequence = sequence + 1
  local token = tokens[((sequence - 1) % #tokens) + 1]
  return wrk.format("POST", "/api/seckill/activities/1/orders", {
    ["Authorization"] = "Bearer " .. token,
    ["Content-Type"] = "application/json"
  })
end
