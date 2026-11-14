package dev.upshift.overlay;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import dev.upshift.Upshift;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public final class HardwareMonitor {
    private static final HardwareMonitor INSTANCE = new HardwareMonitor();
    private static final int SENSOR_TEMPERATURE_GPU = 0;
    private static final int CLOCK_GRAPHICS = 0;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static final String[] NVML_CANDIDATES = WINDOWS
        ? new String[]{"nvml", "nvidia-ml", "C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvml.dll"}
        : new String[]{"nvidia-ml", "nvml"};
    private volatile String gpuName = "GPU";
    private volatile float gpuLoad = Float.NaN;
    private volatile float gpuTemp = Float.NaN;
    private volatile float gpuClock = Float.NaN;
    private volatile float vramUsedMb = Float.NaN;
    private volatile float vramTotalMb = Float.NaN;
    private volatile float cpuLoad = Float.NaN;
    private volatile float cpuTemp = Float.NaN;
    private volatile float cpuClock = Float.NaN;
    private volatile float cpuPower = Float.NaN;
    private volatile float gpuPower = Float.NaN;
    private volatile float gpuFan = Float.NaN;
    private volatile float ramUsedGb = Float.NaN;
    private volatile float ramTotalGb = Float.NaN;
    private volatile int cpuThreads = Runtime.getRuntime().availableProcessors();
    private final AtomicBoolean started = new AtomicBoolean();
    private long lastCpuTotal = -1L;
    private long lastCpuIdle = -1L;
    private NativeLibrary nvml;
    private Pointer nvmlDevice;
    private Path nvidiaHwmon;
    private Kernel32 kernel32;
    private PowrProf powrProf;
    private WmiSensors wmi;
    private boolean wmiUnavailable;
    private long lastWinIdle = -1L;
    private long lastWinKernel = -1L;
    private long lastWinUser = -1L;
    private boolean loggedWindows;

    private HardwareMonitor() {
    }

    public static HardwareMonitor get() {
        return INSTANCE;
    }

    public void start() {
        if (!this.started.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::loop, "upshift-hardware");
        thread.setDaemon(true);
        thread.setPriority(1);
        thread.start();
    }

    private void loop() {
        this.initNvidia();
        while (true) {
            try {
                this.sampleCpu();
                this.sampleMemory();
                this.sampleGpu();
                this.mergeWmi();
            }
            catch (Throwable t) {
                Upshift.LOG.debug("hardware sample failed", t);
            }
            try {
                Thread.sleep(500L);
            }
            catch (InterruptedException e) {
                return;
            }
        }
    }

    private void initNvidia() {
        for (String candidate : NVML_CANDIDATES) {
            if (this.openNvml(candidate)) {
                return;
            }
        }
        Upshift.LOG.debug("NVML unavailable; GPU metrics will be limited");
    }

    private boolean openNvml(String library) {
        try {
            NativeLibrary candidate = NativeLibrary.getInstance(library);
            if (candidate.getFunction("nvmlInit_v2").invokeInt(new Object[0]) != 0) {
                return false;
            }
            PointerByReference handle = new PointerByReference();
            if (candidate.getFunction("nvmlDeviceGetHandleByIndex_v2").invokeInt(new Object[]{0, handle}) != 0) {
                return false;
            }
            this.nvmlDevice = handle.getValue();
            Memory name = new Memory(96L);
            if (candidate.getFunction("nvmlDeviceGetName").invokeInt(new Object[]{this.nvmlDevice, name, 96}) == 0) {
                this.gpuName = name.getString(0L);
            }
            this.nvml = candidate;
            this.tasklistHwmon();
            Upshift.LOG.info("hardware monitor: NVML online for {}", (Object)this.gpuName);
            return true;
        }
        catch (Throwable t) {
            Upshift.LOG.debug("NVML library {} unavailable: {}", (Object)library, (Object)t.toString());
            return false;
        }
    }

    private void tasklistHwmon() {
        Path drm = Path.of("/sys/class/drm");
        if (!Files.isDirectory(drm)) {
            return;
        }
        try (Stream<Path> cards = Files.list(drm)) {
            List<Path> list = cards.filter(p -> p.getFileName().toString().matches("card\\d+")).toList();
            for (Path card : list) {
                Path device = card.resolve("device");
                if (!Files.exists(device.resolve("vendor"))) {
                    continue;
                }
                try {
                    String vendor = Files.readString(device.resolve("vendor")).trim();
                    if (!"0x10de".equalsIgnoreCase(vendor)) {
                        continue;
                    }
                } catch (IOException ignored) {
                    continue;
                }
                Path hwmonRoot = device.resolve("hwmon");
                if (!Files.isDirectory(hwmonRoot)) {
                    continue;
                }
                try (Stream<Path> entries = Files.list(hwmonRoot)) {
                    for (Path entry : entries.toList()) {
                        if (!Files.exists(entry.resolve("temp1_input"))) {
                            continue;
                        }
                        this.nvidiaHwmon = entry;
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void sampleGpu() {
        if (this.nvml != null && this.nvmlDevice != null) {
            try {
                Memory utilization = new Memory(8L);
                if (this.nvml.getFunction("nvmlDeviceGetUtilizationRates").invokeInt(new Object[]{this.nvmlDevice, utilization}) == 0) {
                    this.gpuLoad = utilization.getInt(0L);
                }
                Memory temperature = new Memory(4L);
                if (this.nvml.getFunction("nvmlDeviceGetTemperature").invokeInt(new Object[]{this.nvmlDevice, 0, temperature}) == 0) {
                    this.gpuTemp = temperature.getInt(0L);
                }
                Memory clock = new Memory(4L);
                if (this.nvml.getFunction("nvmlDeviceGetClockInfo").invokeInt(new Object[]{this.nvmlDevice, 0, clock}) == 0) {
                    this.gpuClock = clock.getInt(0L);
                }
                Memory memory = new Memory(24L);
                if (this.nvml.getFunction("nvmlDeviceGetMemoryInfo").invokeInt(new Object[]{this.nvmlDevice, memory}) == 0) {
                    long total = memory.getLong(0L);
                    long used = memory.getLong(16L);
                    this.vramTotalMb = (float)total / 1048576.0f;
                    this.vramUsedMb = (float)used / 1048576.0f;
                }
                Memory fan = new Memory(4L);
                if (this.nvml.getFunction("nvmlDeviceGetFanSpeed").invokeInt(new Object[]{this.nvmlDevice, fan}) == 0) {
                    this.gpuFan = fan.getInt(0L);
                }
                Memory power = new Memory(4L);
                if (this.nvml.getFunction("nvmlDeviceGetPowerUsage").invokeInt(new Object[]{this.nvmlDevice, power}) == 0) {
                    this.gpuPower = (float)power.getInt(0L) / 1000.0f;
                }
                return;
            }
            catch (Throwable ignored) {
                this.nvml = null;
            }
        }
        if (!WINDOWS) {
            this.sampleGpuSysfs();
        }
    }

    private void sampleGpuSysfs() {
        try (Stream<Path> cards = Files.list(Path.of("/sys/class/drm"))) {
            for (Path card : cards.toList()) {
                Path freq;
                Path device = card.resolve("device");
                Path busy = device.resolve("gpu_busy_percent");
                if (Files.isRegularFile(busy, new LinkOption[0])) {
                    this.gpuLoad = Float.parseFloat(Files.readString(busy).trim());
                    Path hwmon = HardwareMonitor.firstHwmon(device);
                    if (hwmon != null) {
                        this.readTemp(hwmon, true);
                    }
                }
                if (!Files.isRegularFile(freq = card.resolve("gt_cur_freq_mhz"), new LinkOption[0])) continue;
                this.gpuClock = Float.parseFloat(Files.readString(freq).trim());
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private void readTemp(Path hwmon, boolean gpu) {
        Path temp = hwmon.resolve("temp1_input");
        if (!Files.isRegularFile(temp, new LinkOption[0])) {
            return;
        }
        try {
            float celsius = Float.parseFloat(Files.readString(temp).trim()) / 1000.0f;
            if (gpu) {
                this.gpuTemp = celsius;
            } else {
                this.cpuTemp = celsius;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private static Path firstHwmon(Path device) {
        Path root = device.resolve("hwmon");
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(p -> Files.exists(p.resolve("temp1_input"))).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void sampleCpu() {
        if (WINDOWS) {
            this.sampleCpuWindows();
            return;
        }
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/stat", new String[0]));
            for (String line : lines) {
                if (!line.startsWith("cpu ")) continue;
                String[] parts = line.trim().split("\\s+");
                long total = 0L;
                long idle = 0L;
                for (int i = 1; i < parts.length; ++i) {
                    long value = Long.parseLong(parts[i]);
                    total += value;
                    if (i != 4 && i != 5) continue;
                    idle += value;
                }
                if (this.lastCpuTotal > 0L) {
                    long dTotal = total - this.lastCpuTotal;
                    long dIdle = idle - this.lastCpuIdle;
                    if (dTotal > 0L) {
                        this.cpuLoad = 100.0f * (float)(dTotal - dIdle) / (float)dTotal;
                    }
                }
                this.lastCpuTotal = total;
                this.lastCpuIdle = idle;
                break;
            }
        }
        catch (Exception lines) {
            // empty catch block
        }
        try {
            ArrayList<String> info = new ArrayList<String>(Files.readAllLines(Path.of("/proc/cpuinfo", new String[0])));
            for (String line : info) {
                if (!line.startsWith("cpu MHz")) continue;
                int colon = line.indexOf(58);
                if (colon > 0) {
                    this.cpuClock = Float.parseFloat(line.substring(colon + 1).trim()) / 1000.0f;
                }
                break;
            }
        }
        catch (Exception info) {
            // empty catch block
        }
        if (Float.isNaN(this.cpuTemp)) {
            Path coretemp = HardwareMonitor.findHwmonByName("coretemp", "k10temp", "zenpower");
            if (coretemp != null) {
                this.readTemp(coretemp, false);
            }
        }
    }

    private static Path findHwmonByName(String ... names) {
        Path root = Path.of("/sys/class/hwmon", new String[0]);
        if (!Files.isDirectory(root, new LinkOption[0])) {
            return null;
        }
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                Path name = entry.resolve("name");
                if (!Files.isRegularFile(name, new LinkOption[0])) continue;
                String value = Files.readString(name).trim();
                for (String wanted : names) {
                    if (!wanted.equalsIgnoreCase(value)) continue;
                    Path path = entry;
                    return path;
                }
            }
        }
        catch (IOException iOException) {
            // empty catch block
        }
        return null;
    }

    private void sampleMemory() {
        if (WINDOWS) {
            this.sampleMemoryWindows();
            return;
        }
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo", new String[0]))) {
                if (line.startsWith("MemTotal:")) {
                    this.ramTotalGb = HardwareMonitor.parseKb(line) / 1048576.0f;
                    continue;
                }
                if (!line.startsWith("MemAvailable:")) continue;
                float available = HardwareMonitor.parseKb(line) / 1048576.0f;
                if (Float.isNaN(this.ramTotalGb)) continue;
                this.ramUsedGb = this.ramTotalGb - available;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
    }

    private static float parseKb(String line) {
        String[] parts = line.trim().split("\\s+");
        return parts.length > 1 ? Float.parseFloat(parts[1]) : Float.NaN;
    }

    private void sampleCpuWindows() {
        try {
            if (this.kernel32 == null) {
                this.kernel32 = Native.load("kernel32", Kernel32.class);
            }
            FileTime idle = new FileTime();
            FileTime kernel = new FileTime();
            FileTime user = new FileTime();
            if (this.kernel32.GetSystemTimes(idle, kernel, user)) {
                long idleTime = idle.value();
                long kernelTime = kernel.value();
                long userTime = user.value();
                if (this.lastWinKernel >= 0L) {
                    long total = kernelTime - this.lastWinKernel + (userTime - this.lastWinUser);
                    long idleDelta = idleTime - this.lastWinIdle;
                    if (total > 0L) {
                        this.cpuLoad = Math.max(0.0f, Math.min(100.0f, 100.0f * (float)(total - idleDelta) / (float)total));
                    }
                }
                this.lastWinIdle = idleTime;
                this.lastWinKernel = kernelTime;
                this.lastWinUser = userTime;
            }
        }
        catch (Throwable t) {
            this.kernel32 = null;
        }
        this.sampleCpuClockWindows();
        if (!this.loggedWindows && !Float.isNaN(this.cpuLoad)) {
            this.loggedWindows = true;
            Upshift.LOG.info("hardware monitor: Windows CPU and memory sampling online");
        }
    }

    /**
     * Windows reports the current core clock through {@code CallNtPowerInformation}. The
     * output is one {@code PROCESSOR_POWER_INFORMATION} record per logical processor; the
     * record is six 32-bit fields, and {@code CurrentMhz} sits at offset 8.
     */
    private void sampleCpuClockWindows() {
        try {
            if (this.powrProf == null) {
                this.powrProf = Native.load("powrprof", PowrProf.class);
            }
            int threads = Math.max(1, this.cpuThreads);
            int bytes = threads * 24;
            Memory out = new Memory((long)bytes);
            if (this.powrProf.CallNtPowerInformation(11, null, 0, out, bytes) == 0) {
                int count = Math.max(1, Math.min(threads, out.getInt(0L)));
                long sum = 0L;
                int used = 0;
                for (int index = 0; index < count; ++index) {
                    int mhz = out.getInt((long)index * 24L + 8L);
                    if (mhz > 0) {
                        sum += mhz;
                        ++used;
                    }
                }
                if (used > 0) {
                    this.cpuClock = (float)sum / (float)used / 1000.0f;
                }
            }
        }
        catch (Throwable t) {
            this.powrProf = null;
        }
    }

    private void sampleMemoryWindows() {
        try {
            if (this.kernel32 == null) {
                this.kernel32 = Native.load("kernel32", Kernel32.class);
            }
            MemoryStatusEx status = new MemoryStatusEx();
            if (this.kernel32.GlobalMemoryStatusEx(status)) {
                this.ramTotalGb = (float)status.totalPhys / 1073741824.0f;
                this.ramUsedGb = (float)(status.totalPhys - status.availPhys) / 1073741824.0f;
            }
        }
        catch (Throwable t) {
            this.kernel32 = null;
        }
    }

    /**
     * Fills the metrics Windows does not expose directly from the optional WMI sensor
     * providers. NVML readings win for the GPU; WMI only supplies the gaps.
     */
    private void mergeWmi() {
        if (!WINDOWS || this.wmiUnavailable) {
            return;
        }
        try {
            if (this.wmi == null) {
                this.wmi = new WmiSensors();
            }
            WmiSensors.Sample sample = this.wmi.sample();
            if (Float.isNaN(this.cpuTemp)) {
                this.cpuTemp = sample.cpuTemp;
            }
            this.cpuPower = sample.cpuPower;
            if (this.nvml == null) {
                this.gpuLoad = sample.gpuLoad;
                this.gpuTemp = sample.gpuTemp;
                this.gpuClock = sample.gpuClock;
                this.gpuPower = sample.gpuPower;
                this.vramUsedMb = sample.gpuMemoryUsedMb;
                this.vramTotalMb = sample.gpuMemoryTotalMb;
            }
            else if (Float.isNaN(this.gpuPower)) {
                this.gpuPower = sample.gpuPower;
            }
        }
        catch (Throwable t) {
            this.wmiUnavailable = true;
            this.wmi = null;
            Upshift.LOG.warn("hardware sensors over WMI are unavailable; CPU temperature and power stay hidden", t);
        }
    }

    public String gpuName() {
        return this.gpuName;
    }

    public float gpuLoad() {
        return this.gpuLoad;
    }

    public float gpuTemp() {
        return this.gpuTemp;
    }

    public float gpuClock() {
        return this.gpuClock;
    }

    public float vramUsedMb() {
        return this.vramUsedMb;
    }

    public float vramTotalMb() {
        return this.vramTotalMb;
    }

    public float cpuLoad() {
        return this.cpuLoad;
    }

    public float cpuTemp() {
        return this.cpuTemp;
    }

    public float cpuClock() {
        return this.cpuClock;
    }

    public float cpuPower() {
        return this.cpuPower;
    }

    public float gpuPower() {
        return this.gpuPower;
    }

    public float gpuFan() {
        return this.gpuFan;
    }

    public float ramUsedGb() {
        return this.ramUsedGb;
    }

    public float ramTotalGb() {
        return this.ramTotalGb;
    }

    public int cpuThreads() {
        return this.cpuThreads;
    }

    public String format(String format, float value, String suffix, int decimals) {
        if (Float.isNaN(value)) {
            return "--" + suffix;
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", Float.valueOf(value)) + suffix;
    }

    private interface Kernel32
    extends Library {
        boolean GetSystemTimes(FileTime var1, FileTime var2, FileTime var3);

        boolean GlobalMemoryStatusEx(MemoryStatusEx var1);
    }

    private interface PowrProf
    extends Library {
        int CallNtPowerInformation(int var1, Pointer var2, int var3, Pointer var4, int var5);
    }

    public static final class FileTime
    extends Structure {
        public int low;
        public int high;

        public long value() {
            return Integer.toUnsignedLong(this.high) << 32 | Integer.toUnsignedLong(this.low);
        }

        protected List<String> getFieldOrder() {
            return List.of("low", "high");
        }
    }

    public static final class MemoryStatusEx
    extends Structure {
        public int length;
        public int memoryLoad;
        public long totalPhys;
        public long availPhys;
        public long totalPageFile;
        public long availPageFile;
        public long totalVirtual;
        public long availVirtual;
        public long availExtendedVirtual;

        public MemoryStatusEx() {
            this.length = this.size();
        }

        protected List<String> getFieldOrder() {
            return List.of("length", "memoryLoad", "totalPhys", "availPhys", "totalPageFile", "availPageFile", "totalVirtual", "availVirtual", "availExtendedVirtual");
        }
    }
}
