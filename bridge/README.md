# USB 小夜灯本机桥接

从项目目录运行 `cd bridge`，然后执行 `python nightlight.py --simulate`。模拟模式无需继电器、Minecraft 或第三方 Python 依赖。首次启动在当前目录生成 `bridge.token`，默认监听 `127.0.0.1:17863`。

打开另一个 PowerShell，进入同一 `bridge` 目录，复制以下命令。它会模拟开灯、查看状态，再释放控制会话关灯。

```powershell
$bridgeUrl = 'http://127.0.0.1:17863'
$bridgeHeaders = @{ Authorization = 'Bearer ' + (Get-Content -Raw bridge.token).Trim() }
$bridgeSession = Invoke-RestMethod -Method Post -Uri "$bridgeUrl/v1/session" -Headers $bridgeHeaders
$bridgeBody = @{ session_id = $bridgeSession.session_id; seq = 0; on = $true } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$bridgeUrl/v1/state" -Headers $bridgeHeaders -ContentType 'application/json' -Body $bridgeBody
Start-Sleep -Milliseconds 700
Invoke-RestMethod -Uri "$bridgeUrl/v1/status" -Headers $bridgeHeaders
$bridgeBody = @{ session_id = $bridgeSession.session_id } | ConvertTo-Json
Invoke-RestMethod -Method Delete -Uri "$bridgeUrl/v1/session" -Headers $bridgeHeaders -ContentType 'application/json' -Body $bridgeBody
```

持续控制时每 2 秒向 `POST /v1/heartbeat` 发送含 `session_id` 的 JSON。8 秒未收到心跳时桥接撤销会话并关灯。开关指令使用递增的 `seq`，重试同一目标沿用同一个序号。串口中断会撤销会话，待串口恢复后创建新会话并重新提交当前目标。收到 `relay_reset_pending` 时稍候重试。状态接口的 `last_written_state` 表示最近一次写入结果，物理状态始终显示为未知。

实物到手后，在 `bridge` 目录执行：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m serial.tools.list_ports -v
.\.venv\Scripts\python.exe nightlight.py --port COM5
```

将 COM5 换成通过插拔辨认的继电器端口。确认卖家协议后再启动实物模式。默认使用 9600、8N1 与 LCUS-1 常见开关帧，可用 `--on-hex`、`--off-hex` 指定实物的四字节帧。优先测试模块本身，确认吸合与释放后再接灯。更换 USB 插口导致 COM 编号变化时重新确认端口并重启桥接。按 Ctrl+C 正常退出。
