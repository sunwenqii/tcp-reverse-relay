package dev.sunwenqi.tcpreverserelay.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import java.util.regex.Pattern;

/** Captures application INFO/WARN/ERROR without retaining handshake payloads. */
public final class JournalLogAppender extends AppenderBase<ILoggingEvent> {
    private static final Pattern PORT = Pattern.compile("(?:端口\\s*|port\\[|control \\[|stopListen\\[|client-|control-recv-|重新建立连接\\s*)(\\d{1,5})(?!\\d)");
    @Override protected void append(ILoggingEvent event) {
        if (!RuntimeEvents.enabled() || event.getLevel().toInt() < Level.INFO_INT || !event.getLoggerName().startsWith("dev.sunwenqi.tcpreverserelay")) return;
        String message = event.getFormattedMessage();
        if (message.contains("InteractiveModel(") || event.getLoggerName().endsWith("InteractiveProcessHandler"))
            message = "收到控制消息（握手内容已省略）";
        Integer port = null;
        var matcher = PORT.matcher(message + " " + event.getThreadName());
        if (matcher.find()) { int value = Integer.parseInt(matcher.group(1)); if (value > 0 && value <= 65535) port = value; }
        String detail = event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy());
        RuntimeEvents.log(event.getLevel().toString(), port, message, detail, event.getLoggerName());
    }
}
