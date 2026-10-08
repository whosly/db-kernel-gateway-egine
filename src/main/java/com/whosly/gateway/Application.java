package com.whosly.gateway;

import com.whosly.gateway.adapter.ProtocolAdapter;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.runtime.GatewayListenerRuntime;
import com.whosly.gateway.runtime.GatewayListenerRuntime.ManagedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

/**
 * Main application class for the Multi-Protocol Database Gateway Engine.
 *
 * <p>On context refresh, starts every enabled creatable gateway instance via
 * {@link GatewayListenerRuntime} (same-JVM multi-listener).</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@SpringBootApplication
public class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    @Autowired
    private GatewayListenerRuntime gatewayListenerRuntime;

    /** Legacy single-adapter alias (proxy-* match / default / first bound). */
    @Autowired
    private ProtocolAdapter protocolAdapter;

    @Autowired
    private GatewayConfig gatewayConfig;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @EventListener
    public void onApplicationEvent(ContextRefreshedEvent event) {
        log.info("Starting gateway listener runtime ({} configured instance(s))...",
                gatewayListenerRuntime.list().size());
        gatewayListenerRuntime.startEnabledOnBoot();
        logInfo();
    }

    private void logInfo() {
        log.info("=== Gateway instances ===");
        for (ManagedListener listener : gatewayListenerRuntime.list()) {
            String state = !listener.enabled() ? "DISABLED"
                    : (listener.adapter() == null ? "UNSUPPORTED"
                    : (listener.isRunning() ? "RUNNING" : "STOPPED"));
            log.info("  [{}] id={} dbType={} listen={}:{} target={}:{} status={}",
                    listener.bound() ? "bound" : "unbound",
                    listener.id(),
                    listener.dbType(),
                    listener.listenHost(),
                    listener.listenPort(),
                    listener.targetHost(),
                    listener.targetPort(),
                    state);
        }
        log.info("Legacy /gateway/* adapter: id={} protocol={} port={} running={}",
                gatewayListenerRuntime.getLegacyId(),
                protocolAdapter.getProtocolName(),
                protocolAdapter.getDefaultPort(),
                protocolAdapter.isRunning());
        log.info("Process proxy-* defaults: type={} port={}",
                gatewayConfig.getProxyDbType(), gatewayConfig.getProxyPort());
        log.info("Target defaults: host={} port={} username={} database={}",
                gatewayConfig.getTargetHost(),
                gatewayConfig.getTargetPort(),
                gatewayConfig.getTargetUsername(),
                gatewayConfig.getTargetDatabase());
        // Never log passwords.
    }
}
