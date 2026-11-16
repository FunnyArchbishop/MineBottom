# MineBottom 小夜灯模组 0.3.0

适用于 Windows 上的 Minecraft Java 26.2 或 26.3，两版使用各自的 JAR 和 Java 25。模组直接驱动 LCUS-1 的 CH340 串口。支持单人存档和通过地址加入的多人服务器，服务器及其他玩家可使用原版。单人绑定沿用旧存档位置，多人绑定按服务器地址保存在本机游戏配置目录。每个存档或服务器可绑定一个拉杆。

| Minecraft | 实例示例 | Fabric API | 模组文件 |
| --- | --- | --- | --- |
| 26.2 | `26.2-Fabric 0.19.5` | `0.160.0+26.2` | `build/minebottom-0.3.0+26.2.jar` |
| 26.3 | `26.3-Fabric 0.20.1` | `0.165.2+26.3` | `build/minebottom-0.3.0+26.3.jar` |

1. 退出 Minecraft。若运行过旧版 Python 桥接，先关闭它，腾出 COM 口。
2. 从目标 PCL 实例的 mods 文件夹移出旧 MineBottom JAR，放入表中对应的 JAR。
3. 插好继电器，启动对应 Fabric 实例并进入服务器或存档。准星对准原版拉杆，手动输入 `/nightlight bind`，拨动拉杆测试灯。这条命令由客户端处理，无需服务器 OP 权限。

| 命令 | 用途 |
| --- | --- |
| `/nightlight status` | 查看绑定位置与串口状态 |
| `/nightlight unbind` | 解绑并尝试关灯 |
| `/nightlight test on`、`/nightlight test off` | 未绑定时直接测试灯 |

只监测客户端已加载的绑定位置。同一维度内，其他玩家拨动拉杆也能触发。离开区块、切换维度、拉杆被拆掉或退出服务器后，模组尝试关灯。位置再次加载后，模组重新读取拉杆状态。服务器地址换成别名或变更端口后，需要为该地址重新绑定。

模组寻找当前已连接的 CH340（USB VID 1A86、PID 7523）。如果同时插有多个匹配串口，会提示暂时只保留继电器。强制结束进程或断开 USB 时可能来不及执行关灯，串口状态只反映指令写入情况。

构建需要已安装的 Fabric 实例和 JDK 25。实例名称不同时可用 -Instance 指定。

```powershell
./mod/build.ps1 -MinecraftRoot 'C:\path\to\.minecraft' -Jdk 'C:\path\to\jdk-25' -MinecraftVersion 26.2
```
