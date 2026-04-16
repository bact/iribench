package cc.bact.sameasbench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;

public class Measurement {
    public double wallMs;
    public double cpuUserMs;   // thread CPU time (user+sys combined in JVM)
    public double peakMemoryMb;

    public static Measurement average(List<Measurement> list) {
        if (list.isEmpty()) return new Measurement();
        Measurement avg = new Measurement();
        avg.wallMs       = list.stream().mapToDouble(m -> m.wallMs).average().orElse(0);
        avg.cpuUserMs    = list.stream().mapToDouble(m -> m.cpuUserMs).average().orElse(0);
        avg.peakMemoryMb = list.stream().mapToDouble(m -> m.peakMemoryMb).max().orElse(0);
        return avg;
    }

    @FunctionalInterface
    public interface MeasuredTask {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface MeasuredSupplier<T> {
        T get() throws Exception;
    }

    public record MeasuredResult<T>(T value, Measurement measurement) {}

    public static Measurement measure(MeasuredTask task) throws Exception {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        long cpuBefore = tmx.isCurrentThreadCpuTimeSupported() ? tmx.getCurrentThreadCpuTime() : 0;
        long t0 = System.nanoTime();

        task.run();

        long wallNs = System.nanoTime() - t0;
        long cpuNs = tmx.isCurrentThreadCpuTimeSupported() ? tmx.getCurrentThreadCpuTime() - cpuBefore : 0;
        long memAfter = rt.totalMemory() - rt.freeMemory();

        Measurement m = new Measurement();
        m.wallMs       = wallNs / 1_000_000.0;
        m.cpuUserMs    = cpuNs  / 1_000_000.0;
        m.peakMemoryMb = Math.max(0, memAfter - memBefore) / (1024.0 * 1024.0);
        return m;
    }

    public static <T> MeasuredResult<T> measureWithResult(MeasuredSupplier<T> task) throws Exception {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        long cpuBefore = tmx.isCurrentThreadCpuTimeSupported() ? tmx.getCurrentThreadCpuTime() : 0;
        long t0 = System.nanoTime();

        T value = task.get();

        long wallNs = System.nanoTime() - t0;
        long cpuNs = tmx.isCurrentThreadCpuTimeSupported() ? tmx.getCurrentThreadCpuTime() - cpuBefore : 0;
        long memAfter = rt.totalMemory() - rt.freeMemory();

        Measurement m = new Measurement();
        m.wallMs       = wallNs / 1_000_000.0;
        m.cpuUserMs    = cpuNs  / 1_000_000.0;
        m.peakMemoryMb = Math.max(0, memAfter - memBefore) / (1024.0 * 1024.0);
        return new MeasuredResult<>(value, m);
    }
}
