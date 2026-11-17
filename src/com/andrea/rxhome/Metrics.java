package com.andrea.rxhome;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/** rexserver's CPU, temperature, RAM and uptime from Prometheus node_exporter (port 9100). */
final class Metrics {

    static final class Sample {
        double cpuIdle = -1, cpuTotal = -1, tempC = -1, memTotal = -1, memAvailable = -1, boot = -1, now = -1;

        double ramUsedGb() { return (memTotal - memAvailable) / 1e9; }
        double ramTotalGb() { return memTotal / 1e9; }
        long uptimeDays() { return Math.round((now - boot) / 86400); }
    }

    private Metrics() {}

    static Sample parse(String text) {
        Sample s = new Sample();
        double idle = 0, total = 0;
        boolean cpu = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            int sp = line.lastIndexOf(' ');
            if (sp < 0) continue;
            double v;
            try {
                v = Double.parseDouble(line.substring(sp + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            String name = line.substring(0, sp);
            if (name.startsWith("node_cpu_seconds_total{")) {
                cpu = true;
                total += v;
                if (name.contains("mode=\"idle\"") || name.contains("mode=\"iowait\"")) idle += v;
            } else if (name.startsWith("node_hwmon_temp_celsius{") && name.contains("coretemp")) {
                s.tempC = Math.max(s.tempC, v);
            } else if (name.startsWith("node_thermal_zone_temp{") && s.tempC < 0) {
                s.tempC = v;
            } else if (name.equals("node_memory_MemTotal_bytes")) {
                s.memTotal = v;
            } else if (name.equals("node_memory_MemAvailable_bytes")) {
                s.memAvailable = v;
            } else if (name.equals("node_boot_time_seconds")) {
                s.boot = v;
            } else if (name.equals("node_time_seconds")) {
                s.now = v;
            }
        }
        if (cpu) {
            s.cpuIdle = idle;
            s.cpuTotal = total;
        }
        return s;
    }

    /** Busy share of CPU time between two samples, 0–100; -1 if unknown. */
    static double cpuPercent(Sample a, Sample b) {
        double dt = b.cpuTotal - a.cpuTotal;
        if (a.cpuTotal < 0 || b.cpuTotal < 0 || dt <= 0) return -1;
        return Math.max(0, Math.min(100, 100 * (1 - (b.cpuIdle - a.cpuIdle) / dt)));
    }

    /** E.g. "CPU 10% · 44°C · RAM 3.3/8.3 GB · up 6d"; parts that are unknown are left out. */
    static String line(Sample a, Sample b) {
        List<String> parts = new ArrayList<>();
        double cpu = cpuPercent(a, b);
        if (cpu >= 0) parts.add(String.format(Locale.ROOT, "CPU %d%%", Math.round(cpu)));
        if (b.tempC >= 0) parts.add(String.format(Locale.ROOT, "%d°C", Math.round(b.tempC)));
        if (b.memTotal > 0 && b.memAvailable >= 0) {
            parts.add(String.format(Locale.ROOT, "RAM %.1f/%.1f GB", b.ramUsedGb(), b.ramTotalGb()));
        }
        if (b.boot > 0 && b.now > b.boot) parts.add("up " + b.uptimeDays() + "d");
        return String.join(" · ", parts);
    }

    /** Two samples 0.7 s apart (CPU % needs a difference); null if the exporter can't be reached. */
    static String fetch(String metricsUrl) {
        String first = get(metricsUrl);
        if (first == null) return null;
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        String second = get(metricsUrl);
        if (second == null) return null;
        String l = line(parse(first), parse(second));
        return l.isEmpty() ? null : l;
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8")) {
                return s.useDelimiter("\\A").hasNext() ? s.next() : "";
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
