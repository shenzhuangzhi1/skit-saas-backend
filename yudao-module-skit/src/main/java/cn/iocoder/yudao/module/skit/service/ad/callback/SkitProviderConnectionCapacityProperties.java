package cn.iocoder.yudao.module.skit.service.ad.callback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounded local admission settings for one verified provider connection. */
@Component
@ConfigurationProperties(prefix = "skit.ad.provider-callback-capacity")
public class SkitProviderConnectionCapacityProperties {

    private int maximumTrackedConnections = 4096;
    private int maximumConcurrentPerConnection = 8;
    private int peakPermitsPerSecond = 120;
    private int burstPermits = 240;

    public int getMaximumTrackedConnections() {
        return maximumTrackedConnections;
    }

    public void setMaximumTrackedConnections(int maximumTrackedConnections) {
        this.maximumTrackedConnections = maximumTrackedConnections;
    }

    public int getMaximumConcurrentPerConnection() {
        return maximumConcurrentPerConnection;
    }

    public void setMaximumConcurrentPerConnection(int maximumConcurrentPerConnection) {
        this.maximumConcurrentPerConnection = maximumConcurrentPerConnection;
    }

    public int getPeakPermitsPerSecond() {
        return peakPermitsPerSecond;
    }

    public void setPeakPermitsPerSecond(int peakPermitsPerSecond) {
        this.peakPermitsPerSecond = peakPermitsPerSecond;
    }

    public int getBurstPermits() {
        return burstPermits;
    }

    public void setBurstPermits(int burstPermits) {
        this.burstPermits = burstPermits;
    }

    @Override
    public String toString() {
        return "SkitProviderConnectionCapacityProperties{maximumTrackedConnections="
                + maximumTrackedConnections + ", maximumConcurrentPerConnection="
                + maximumConcurrentPerConnection + ", peakPermitsPerSecond="
                + peakPermitsPerSecond + ", burstPermits=" + burstPermits + '}';
    }
}
