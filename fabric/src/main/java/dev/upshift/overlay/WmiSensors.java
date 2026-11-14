package dev.upshift.overlay;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.WbemcliUtil;
import com.sun.jna.platform.win32.Ole32;
import dev.upshift.Upshift;
import java.util.Locale;

/**
 * Reads the sensors other monitoring software publishes through WMI.
 *
 * <p>Windows exposes no plain API for CPU temperature or package power. LibreHardwareMonitor and
 * OpenHardwareMonitor publish them under their own WMI namespace, and many boards answer the ACPI
 * thermal zone query. All three are optional: when none is present the caller keeps its own
 * readings and the panel simply omits those fields.</p>
 *
 * <p>The queries run on the hardware sampling thread, never on the render thread, and are throttled
 * to one refresh per second.</p>
 */
final class WmiSensors {
    private static final long REFRESH_NS = 1_000_000_000L;
    private static final long PROBE_INTERVAL_NS = 20_000_000_000L;
    private static final int ACPI_ATTEMPTS = 3;
    private static final String[] MONITOR_NAMESPACES = {"ROOT\\LibreHardwareMonitor", "ROOT\\OpenHardwareMonitor"};

    private final Sample sample = new Sample();
    private String namespace;
    private String source;
    private long nextProbeNs;
    private long nextAcpiNs;
    private long lastRefreshNs;
    private int acpiAttempts;
    private boolean comReady;

    static final class Sample {
        float cpuTemp = Float.NaN;
        float cpuPower = Float.NaN;
        float gpuLoad = Float.NaN;
        float gpuTemp = Float.NaN;
        float gpuClock = Float.NaN;
        float gpuPower = Float.NaN;
        float gpuMemoryUsedMb = Float.NaN;
        float gpuMemoryTotalMb = Float.NaN;

        void reset() {
            this.cpuTemp = Float.NaN;
            this.cpuPower = Float.NaN;
            this.gpuLoad = Float.NaN;
            this.gpuTemp = Float.NaN;
            this.gpuClock = Float.NaN;
            this.gpuPower = Float.NaN;
            this.gpuMemoryUsedMb = Float.NaN;
            this.gpuMemoryTotalMb = Float.NaN;
        }
    }

    Sample sample() {
        long now = System.nanoTime();
        if (now - this.lastRefreshNs < REFRESH_NS) {
            return this.sample;
        }
        this.lastRefreshNs = now;
        if (!this.comReady) {
            this.comReady = true;
            this.initializeCom();
        }
        if (this.namespace == null && now >= this.nextProbeNs) {
            this.nextProbeNs = now + PROBE_INTERVAL_NS;
            this.probeMonitorNamespace();
        }
        if (this.namespace != null) {
            try {
                this.readMonitor();
            }
            catch (Throwable t) {
                Upshift.LOG.debug("sensor read from {} failed: {}", this.source, t.toString());
                this.namespace = null;
                this.source = null;
                this.sample.reset();
            }
        }
        if (Float.isNaN(this.sample.cpuTemp) && this.acpiAttempts < ACPI_ATTEMPTS && now >= this.nextAcpiNs) {
            this.nextAcpiNs = now + PROBE_INTERVAL_NS;
            ++this.acpiAttempts;
            this.readAcpi();
        }
        return this.sample;
    }

    private void initializeCom() {
        try {
            Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
        }
        catch (Throwable t) {
            Upshift.LOG.debug("COM initialization for hardware sensors failed: {}", t.toString());
        }
    }

    private void probeMonitorNamespace() {
        for (String candidate : MONITOR_NAMESPACES) {
            try {
                if (!WbemcliUtil.hasNamespace(candidate)) {
                    continue;
                }
                this.namespace = candidate;
                this.source = candidate.endsWith("LibreHardwareMonitor") ? "LibreHardwareMonitor" : "OpenHardwareMonitor";
                Upshift.LOG.info("hardware monitor: {} sensors online", this.source);
                return;
            }
            catch (Throwable t) {
                Upshift.LOG.debug("could not probe {}: {}", candidate, t.toString());
            }
        }
    }

    private void readMonitor() {
        WbemcliUtil.WmiQuery<SensorProperty> query = new WbemcliUtil.WmiQuery<>(this.namespace, "Sensor", SensorProperty.class);
        WbemcliUtil.WmiResult<SensorProperty> result = query.execute();
        int rows = result.getResultCount();
        float cpuTemp = Float.NaN;
        int cpuTempScore = 0;
        float cpuPower = Float.NaN;
        float gpuPower = Float.NaN;
        float gpuLoad = Float.NaN;
        float gpuTemp = Float.NaN;
        float gpuClock = Float.NaN;
        float gpuUsed = Float.NaN;
        float gpuTotal = Float.NaN;
        for (int index = 0; index < rows; ++index) {
            Object raw = result.getValue(SensorProperty.VALUE, index);
            if (!(raw instanceof Number number)) {
                continue;
            }
            float value = number.floatValue();
            if (Float.isNaN(value)) {
                continue;
            }
            String name = WmiSensors.text(result.getValue(SensorProperty.NAME, index));
            String type = WmiSensors.text(result.getValue(SensorProperty.SENSORTYPE, index));
            String identifier = WmiSensors.text(result.getValue(SensorProperty.IDENTIFIER, index)).toLowerCase(Locale.ROOT);
            String lower = name.toLowerCase(Locale.ROOT);
            boolean gpu = identifier.contains("gpu") || lower.startsWith("gpu");
            boolean cpu = !gpu && (identifier.contains("cpu") || identifier.contains("core") || lower.contains("cpu") || lower.contains("core"));
            if (type.equalsIgnoreCase("Temperature")) {
                if (cpu) {
                    int score = lower.contains("package") || lower.contains("total") || lower.contains("tctl") || lower.contains("tdie") ? 3 : (lower.contains("core") ? 2 : 1);
                    if (score > cpuTempScore || score == cpuTempScore && value > cpuTemp) {
                        cpuTemp = value;
                        cpuTempScore = score;
                    }
                }
                else if (gpu && lower.contains("core") && (Float.isNaN(gpuTemp) || value > gpuTemp)) {
                    gpuTemp = value;
                }
            }
            else if (type.equalsIgnoreCase("Load")) {
                if (gpu && lower.startsWith("gpu core")) {
                    gpuLoad = value;
                }
            }
            else if (type.equalsIgnoreCase("Clock")) {
                if (gpu && lower.startsWith("gpu core")) {
                    gpuClock = value;
                }
            }
            else if (type.equalsIgnoreCase("Power")) {
                if (cpu && lower.contains("package")) {
                    cpuPower = value;
                }
                else if (gpu) {
                    gpuPower = value;
                }
            }
            else if (type.equalsIgnoreCase("SmallData")) {
                if (lower.equals("gpu memory used")) {
                    gpuUsed = value;
                }
                else if (lower.equals("gpu memory total")) {
                    gpuTotal = value;
                }
            }
        }
        this.sample.cpuTemp = cpuTemp;
        this.sample.cpuPower = cpuPower;
        this.sample.gpuPower = gpuPower;
        this.sample.gpuLoad = gpuLoad;
        this.sample.gpuTemp = gpuTemp;
        this.sample.gpuClock = gpuClock;
        this.sample.gpuMemoryUsedMb = gpuUsed;
        this.sample.gpuMemoryTotalMb = gpuTotal;
    }

    private void readAcpi() {
        try {
            WbemcliUtil.WmiQuery<AcpiProperty> query = new WbemcliUtil.WmiQuery<>("ROOT\\WMI", "MSAcpi_ThermalZoneTemperature", AcpiProperty.class);
            WbemcliUtil.WmiResult<AcpiProperty> result = query.execute();
            float best = Float.NaN;
            for (int index = 0; index < result.getResultCount(); ++index) {
                Object raw = result.getValue(AcpiProperty.CURRENTTEMPERATURE, index);
                if (!(raw instanceof Number number)) {
                    continue;
                }
                float celsius = number.floatValue() / 10.0f - 273.15f;
                if (celsius < -20.0f || celsius > 150.0f) {
                    continue;
                }
                if (Float.isNaN(best) || celsius > best) {
                    best = celsius;
                }
            }
            if (!Float.isNaN(best)) {
                this.sample.cpuTemp = best;
                Upshift.LOG.info("hardware monitor: ACPI thermal zone reports {} C", Math.round(best));
            }
        }
        catch (Throwable t) {
            Upshift.LOG.debug("ACPI thermal zone unavailable: {}", t.toString());
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    enum SensorProperty {
        NAME,
        SENSORTYPE,
        VALUE,
        IDENTIFIER
    }

    enum AcpiProperty {
        CURRENTTEMPERATURE,
        INSTANCENAME
    }
}
