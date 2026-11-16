# MineBottom

拨动 Minecraft 里的拉杆，开关电脑 USB 供电的小夜灯。MineBottom 是 Windows 上的 Fabric 客户端模组，支持单人存档和多人服务器。服务器与其他玩家无需安装，也无需 OP 权限。

| Minecraft Java | Fabric Loader | Fabric API | 构建文件 |
| --- | --- | --- | --- |
| 26.2 | 0.19.5 | 0.160.0+26.2 | minebottom-0.3.0+26.2.jar |
| 26.3 | 0.20.1 | 0.165.2+26.3 | minebottom-0.3.0+26.3.jar |

从 [Releases](https://github.com/FunnyArchbishop/MineBottom/releases) 下载与游戏版本匹配的 JAR，连同对应的 Fabric API 放进客户端 mods 目录。安装 CH340 驱动，连接 LCUS-1 单路 USB 串口继电器，再启动游戏。

进入世界后对准一支原版拉杆，手动输入 `/nightlight bind`。拉杆通电开灯，断电关灯。使用 `/nightlight status` 查看绑定和串口状态，使用 `/nightlight unbind` 解除绑定。多人模式只能读取本机已加载区块内的拉杆状态，离开区块或退出服务器后会尝试关灯。

硬件使用 CH340（VID 1A86、PID 7523）以及恢复供电会自动亮的 5V USB 灯。取电端 V+ 接继电器 COM，NO 接灯端 V+，两端 GND 直连。接线前拔下两个 USB 插头。

详见 [安装与构建](mod/README.md)和[串口连接与排查](docs/serial-connection.md)。[Python 串口桥接](bridge/README.md)可独立调试，日常运行游戏模组无需桥接。

本项目以 [MIT 许可证](LICENSE)发布。
