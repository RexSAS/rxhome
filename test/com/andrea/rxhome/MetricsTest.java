package com.andrea.rxhome;

public class MetricsTest {
    // node_exporter text format, trimmed to what we read (2 CPUs).
    static String sample(double idle0, double user0, double idle1, double user1) {
        return "# HELP node_cpu_seconds_total Seconds the CPUs spent in each mode.\n"
            + "# TYPE node_cpu_seconds_total counter\n"
            + "node_cpu_seconds_total{cpu=\"0\",mode=\"idle\"} " + idle0 + "\n"
            + "node_cpu_seconds_total{cpu=\"0\",mode=\"user\"} " + user0 + "\n"
            + "node_cpu_seconds_total{cpu=\"1\",mode=\"idle\"} " + idle1 + "\n"
            + "node_cpu_seconds_total{cpu=\"1\",mode=\"user\"} " + user1 + "\n"
            + "node_hwmon_temp_celsius{chip=\"platform_coretemp_0\",sensor=\"temp1\"} 41\n"
            + "node_hwmon_temp_celsius{chip=\"platform_coretemp_0\",sensor=\"temp2\"} 44\n"
            + "node_hwmon_temp_crit_celsius{chip=\"platform_coretemp_0\",sensor=\"temp1\"} 100\n"
            + "node_memory_MemTotal_bytes 8.253775872e+09\n"
            + "node_memory_MemAvailable_bytes 4.919148544e+09\n"
            + "node_boot_time_seconds 1.7629e+09\n"
            + "node_time_seconds 1.7634184e+09\n";
    }

    public static void main(String[] a) {
        Metrics.Sample s0 = Metrics.parse(sample(1000, 100, 1000, 100));
        Metrics.Sample s1 = Metrics.parse(sample(1009, 101, 1009, 101)); // 2 busy of 20 cpu-seconds
        assert Math.round(Metrics.cpuPercent(s0, s1)) == 10 : Metrics.cpuPercent(s0, s1);
        assert s1.tempC == 44 : s1.tempC;               // hottest core, not the "crit" limit
        assert Math.abs(s1.ramUsedGb() - 3.33) < 0.01 : s1.ramUsedGb();
        assert Math.abs(s1.ramTotalGb() - 8.25) < 0.01;
        assert s1.uptimeDays() == 6 : s1.uptimeDays();
        assert Metrics.line(s0, s1).equals("CPU 10% · 44°C · RAM 3.3/8.3 GB · up 6d") : Metrics.line(s0, s1);

        // Uptime reads naturally at any length.
        assert Metrics.uptime(45 * 60).equals("45m") : Metrics.uptime(45 * 60);
        assert Metrics.uptime(5 * 3600 + 59 * 60).equals("5h");
        assert Metrics.uptime(6 * 86400 + 20 * 3600).equals("6d");

        // Missing pieces are left out rather than shown as zero.
        Metrics.Sample bare = Metrics.parse("node_memory_MemTotal_bytes 8e9\nnode_memory_MemAvailable_bytes 4e9\n");
        assert Metrics.line(bare, bare).equals("RAM 4.0/8.0 GB") : Metrics.line(bare, bare);
        assert Metrics.line(Metrics.parse(""), Metrics.parse("")).isEmpty();

        // Live (RXHOME_METRICS=http://192.168.1.166:9100/metrics): the full line from the real server.
        String live = System.getenv("RXHOME_METRICS");
        if (live != null) {
            String l = Metrics.fetch(live);
            System.out.println("  live: " + l);
            assert l != null && l.matches("CPU \\d+% · \\d+°C · RAM [\\d.]+/[\\d.]+ GB · up \\d+[mhd]") : l;
        }

        // No exporter on that port: null, quickly.
        long t0 = System.currentTimeMillis();
        assert Metrics.fetch("http://127.0.0.1:9") == null;
        assert System.currentTimeMillis() - t0 < 2000;
    }
}
