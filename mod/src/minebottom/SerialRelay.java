package minebottom;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.SetupApi;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg.HKEY;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class SerialRelay {
    private static final byte[] ON = {(byte) 0xA0, 1, 1, (byte) 0xA2};
    private static final byte[] OFF = {(byte) 0xA0, 1, 0, (byte) 0xA1};
    private static final long SWITCH_INTERVAL = TimeUnit.MILLISECONDS.toNanos(500);
    private static final int DCB_SIZE = 28;

    private interface CommState extends StdCallLibrary {
        CommState INSTANCE = Native.load("kernel32", CommState.class);

        boolean GetCommState(WinNT.HANDLE portHandle, Pointer settings);
        boolean SetCommState(WinNT.HANDLE portHandle, Pointer settings);
    }

    private volatile Boolean target;
    private volatile String status = "空闲";
    private WinNT.HANDLE handle;
    private String port;
    private boolean sent;
    private boolean mayBeOn;
    private long lastWrite;
    private long retryAfter;
    private boolean stopped;

    SerialRelay() {
        var worker = Executors.newSingleThreadScheduledExecutor(task ->
            Thread.ofPlatform().daemon().name("MineBottom serial").unstarted(task));
        worker.scheduleWithFixedDelay(this::sync, 0, 100, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "MineBottom lights off"));
    }

    void setTarget(Boolean target) {
        this.target = target;
    }

    String status() {
        return status;
    }

    private synchronized void sync() {
        if (stopped) return;
        Boolean desired = target;
        if (desired == null && handle == null && !mayBeOn) return;
        if (!Platform.isWindows()) {
            status = "串口控制需要 Windows";
            return;
        }
        long now = System.nanoTime();
        if (handle == null && retryAfter != 0 && now - retryAfter < 0) return;
        try {
            if (handle == null) {
                open();
                write(false);
                if (desired != null) return;
            }
            if (desired == null) {
                if (mayBeOn) write(false);
                closePort();
                status = "已释放串口";
            } else if (now - lastWrite >= SWITCH_INTERVAL) {
                if (desired != sent) write(desired);
                else checkConnection();
            }
        } catch (IOException | RuntimeException | LinkageError error) {
            closePort();
            status = error.getMessage();
            retryAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        }
    }

    private void open() throws IOException {
        port = findRelay();
        var api = Kernel32.INSTANCE;
        var opened = api.CreateFile("\\\\.\\" + port, WinNT.GENERIC_READ | WinNT.GENERIC_WRITE,
            0, null, WinNT.OPEN_EXISTING, 0, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(opened))
            throw windowsError("无法打开 " + port + "，请关闭占用串口的程序");
        handle = opened;
        try (var settings = new Memory(DCB_SIZE)) {
            // Win32 DCB uses one-byte CHAR fields, including XonChar and XoffChar.
            settings.clear();
            settings.setInt(0, DCB_SIZE);
            settings.setInt(4, 9600);
            settings.setInt(8, 1); // fBinary, with hardware and software flow control off.
            settings.setByte(18, (byte) 8); // ByteSize; zero Parity and StopBits mean N1.
            settings.setByte(21, (byte) 17); // XonChar
            settings.setByte(22, (byte) 19); // XoffChar
            if (!CommState.INSTANCE.SetCommState(handle, settings))
                throw windowsError("无法配置 " + port);
        }
        var timeout = new WinBase.COMMTIMEOUTS();
        timeout.WriteTotalTimeoutConstant = new DWORD(1000);
        if (!api.SetCommTimeouts(handle, timeout)) throw windowsError("无法设置串口超时");
    }

    private void checkConnection() throws IOException {
        try (var settings = new Memory(DCB_SIZE)) {
            settings.setInt(0, DCB_SIZE);
            if (!CommState.INSTANCE.GetCommState(handle, settings))
                throw windowsError("串口已断开");
        }
    }

    private static String findRelay() throws IOException {
        var setup = SetupApi.INSTANCE;
        var serialClass = new GUID("{86E0D1E0-8089-11D0-9CE4-08003E301F73}");
        var devices = setup.SetupDiGetClassDevs(serialClass, null, null,
            SetupApi.DIGCF_PRESENT | SetupApi.DIGCF_DEVICEINTERFACE);
        if (WinBase.INVALID_HANDLE_VALUE.equals(devices)) throw windowsError("无法枚举串口");
        var ports = new TreeSet<String>();
        try (var hardwareId = new Memory(4096)) {
            for (int index = 0; ; index++) {
                var device = new SetupApi.SP_DEVINFO_DATA();
                if (!setup.SetupDiEnumDeviceInfo(devices, index, device)) {
                    if (Kernel32.INSTANCE.GetLastError() == 259) break;
                    throw windowsError("无法读取串口设备");
                }
                // SPDRP_HARDWAREID returns a multi-string; the first ID is the most specific.
                if (!setup.SetupDiGetDeviceRegistryProperty(devices, device, 1, null,
                    hardwareId, (int) hardwareId.size(), null)) continue;
                String id = hardwareId.getWideString(0).toUpperCase(Locale.ROOT);
                if (!id.matches("USB\\\\VID_1A86&PID_7523(?:&REV_[0-9A-F]+)?")) continue;
                // Scope global, hardware registry key, query-value rights.
                HKEY key = setup.SetupDiOpenDevRegKey(devices, device, 1, 0, 1, 1);
                if (WinBase.INVALID_HANDLE_VALUE.getPointer().equals(key.getPointer())) continue;
                try {
                    if (Advapi32Util.registryGetValues(key).get("PortName") instanceof String name) {
                        if (name.matches("COM[1-9][0-9]*")) ports.add(name);
                    }
                } finally {
                    Advapi32Util.registryCloseKey(key);
                }
            }
        } finally {
            setup.SetupDiDestroyDeviceInfoList(devices);
        }
        if (ports.size() != 1) throw new IOException(ports.isEmpty()
            ? "未找到 CH340 继电器，请检查 USB 和驱动"
            : "检测到多个 CH340 串口，请只保留继电器：" + ports);
        return ports.first();
    }

    private void write(boolean on) throws IOException {
        if (on) mayBeOn = true;
        byte[] frame = on ? ON : OFF;
        var count = new IntByReference();
        if (!Kernel32.INSTANCE.WriteFile(handle, frame, frame.length, count, null))
            throw windowsError("串口写入失败");
        if (count.getValue() != frame.length) throw new IOException("串口写入不完整");
        sent = on;
        mayBeOn = on;
        lastWrite = System.nanoTime();
        status = port + (on ? " 已发送开灯指令" : " 已发送关灯指令");
    }

    private synchronized void shutdown() {
        stopped = true;
        try {
            if (handle != null && mayBeOn) write(false);
        } catch (IOException ignored) {
            // The COM device may already have been removed.
        } finally {
            closePort();
        }
    }

    private void closePort() {
        if (handle != null) {
            Kernel32.INSTANCE.CloseHandle(handle);
            handle = null;
        }
    }

    private static IOException windowsError(String message) {
        return new IOException(message + "（Windows " + Kernel32.INSTANCE.GetLastError() + "）");
    }
}
