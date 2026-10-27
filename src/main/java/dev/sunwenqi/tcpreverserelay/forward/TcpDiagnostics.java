package dev.sunwenqi.tcpreverserelay.forward;

import java.net.*;
import java.util.concurrent.*;

import dev.sunwenqi.tcpreverserelay.model.interactive.TargetProbeResult;

public final class TcpDiagnostics {
    private static final ThreadPoolExecutor PROBES = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), task -> { Thread t = new Thread(task, "relay-diagnostic"); t.setDaemon(true); return t; });
    private TcpDiagnostics() {}

    public static TargetProbeResult probe(String host, int port) {
        return probe(host, port, 2000);
    }

    public static TargetProbeResult probe(String host, int port, int timeoutMillis) {
        long start = System.nanoTime();
        Future<TargetProbeResult> future = null;
        try {
            future = PROBES.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), timeoutMillis);
                    return result(true, host, port, "TCP 连接成功", start);
                } catch (Exception e) { return result(false, host, port, describe(e), start); }
            });
            return future.get(timeoutMillis + 250L, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return result(false, host, port, "诊断任务繁忙，请稍后重试", start);
        } catch (Exception e) {
            if (future != null) future.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return result(false, host, port, "连接或域名解析超时", start);
        }
    }

    private static TargetProbeResult result(boolean ok, String host, int port, String message, long start) {
        return new TargetProbeResult(ok, host, port, message, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    public static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        if (cause instanceof ConnectException) return "连接被拒绝，请检查目标服务和端口";
        if (cause instanceof UnknownHostException) return "无法解析主机名";
        if (cause instanceof SocketTimeoutException) return "连接超时，请检查地址、网络和防火墙";
        if (cause instanceof TimeoutException) return "等待客户端诊断结果超时";
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length()));
    }
}
