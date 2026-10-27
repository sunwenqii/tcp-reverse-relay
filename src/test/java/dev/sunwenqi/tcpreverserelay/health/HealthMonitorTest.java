package dev.sunwenqi.tcpreverserelay.health;

import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.model.interactive.TargetProbeResult;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HealthMonitorTest {
    @Test void hysteresisPauseDisableAndValidation() {
        try (var monitor = new HealthMonitor(HealthSettings.DEFAULT, (p,s)->{})) {
            var mapping=new AppSettings.Mapping(18080,"127.0.0.1",8080); monitor.reconcile(List.of(mapping),Set.of());
            var bad=new TargetProbeResult(false,"127.0.0.1",8080,"refused",2);
            monitor.record(18080,bad); assertEquals("suspect",monitor.snapshot(18080).state());
            monitor.record(18080,bad); assertEquals("down",monitor.snapshot(18080).state());
            monitor.record(18080,new TargetProbeResult(true,"127.0.0.1",8080,"ok",1)); assertEquals("up",monitor.snapshot(18080).state());
            monitor.reconcile(List.of(mapping),Set.of(18080)); assertEquals("paused",monitor.snapshot(18080).state());
            monitor.reconcile(List.of(mapping),Set.of()); monitor.configure(new HealthSettings(false,15,2000,2));
            monitor.record(18080,bad); assertEquals("disabled",monitor.snapshot(18080).state());
        }
        assertThrows(IllegalArgumentException.class,()->new HealthSettings(true,0,2000,2));
        var properties=new Properties(); properties.setProperty("health.enabled","yes"); assertThrows(IllegalArgumentException.class,()->HealthSettings.from(properties));
        assertEquals("stale",new HealthSnapshot("up","localhost",80,1,1,0,15,"").staleAt(1,60_000).state());
    }
    @Test @Timeout(10) void oldTargetResultIsDiscardedWhileNewTargetProceeds() throws Exception {
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var monitor=new HealthMonitor(HealthSettings.DEFAULT,(p,s)->{},(host,port,timeout)->{
            if(port==8080) { started.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
            return new TargetProbeResult(port==8080,host,port,"result",1);
        })) {
            monitor.reconcile(List.of(new AppSettings.Mapping(18080,8080)),Set.of()); assertTrue(started.await(3,TimeUnit.SECONDS));
            monitor.reconcile(List.of(new AppSettings.Mapping(18080,8081)),Set.of());
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(monitor.snapshot(18080).checkedAt()==0 && System.nanoTime()<until) Thread.sleep(25);
            assertEquals(8081,monitor.snapshot(18080).port()); assertEquals("suspect",monitor.snapshot(18080).state());
            release.countDown(); Thread.sleep(100); assertEquals(8081,monitor.snapshot(18080).port()); assertEquals("suspect",monitor.snapshot(18080).state());
        } finally { release.countDown(); }
    }
}
