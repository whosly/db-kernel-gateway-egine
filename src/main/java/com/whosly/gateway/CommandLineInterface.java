package com.whosly.gateway;

import com.whosly.gateway.adapter.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/**
 * Optional interactive CLI. Default is non-interactive: the gateway auto-starts
 * via {@link Application} lifecycle and does not block on {@code System.in}
 * (safe for {@code spring-boot:run} / service main).
 *
 * <p>Enable the stdin loop with {@code gateway.cli.interactive=true}.</p>
 *
 * @author yueny09@163.com codealy
 * @since 2026-07-02
 */
@Component
public class CommandLineInterface implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);

    private final ProtocolAdapter mySqlProtocolAdapter;

    /**
     * When false (default), skip the interactive stdin loop so process start
     * does not require a TTY. Gateway still starts from {@link Application}.
     */
    @Value("${gateway.cli.interactive:false}")
    private boolean interactiveCli;

    @Autowired
    public CommandLineInterface(ProtocolAdapter mySqlProtocolAdapter) {
        this.mySqlProtocolAdapter = mySqlProtocolAdapter;
    }

    /** Package-visible for unit tests without Spring. */
    void setInteractiveCli(boolean interactiveCli) {
        this.interactiveCli = interactiveCli;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!interactiveCli) {
            log.info("Interactive CLI disabled (gateway.cli.interactive=false). "
                    + "Gateway auto-starts via Application; ops: GET /gateway/status, "
                    + "GET /gateway/metrics, GET /actuator/gateway");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        log.info("Multi-Protocol Database Gateway CLI");
        log.info("Commands: start, stop, status, quit");

        while (running) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) {
                log.info("stdin closed; leaving interactive CLI");
                break;
            }
            String command = scanner.nextLine().trim().toLowerCase();

            switch (command) {
                case "start":
                    startGateway();
                    break;
                case "stop":
                    stopGateway();
                    break;
                case "status":
                    showStatus();
                    break;
                case "quit":
                case "exit":
                    running = false;
                    log.info("Goodbye!");
                    break;
                default:
                    log.info("Unknown command. Available commands: start, stop, status, quit");
                    break;
            }
        }

        scanner.close();
    }

    private void startGateway() {
        try {
            if (!mySqlProtocolAdapter.isRunning()) {
                mySqlProtocolAdapter.start();
                log.info("Gateway started successfully on port {}", mySqlProtocolAdapter.getDefaultPort());
            } else {
                log.info("Gateway is already running");
            }
        } catch (Exception e) {
            log.error("Error starting gateway", e);
        }
    }

    private void stopGateway() {
        try {
            if (mySqlProtocolAdapter.isRunning()) {
                mySqlProtocolAdapter.stop();
                log.info("Gateway stopped successfully");
            } else {
                log.info("Gateway is not running");
            }
        } catch (Exception e) {
            log.error("Error stopping gateway", e);
        }
    }

    private void showStatus() {
        if (mySqlProtocolAdapter.isRunning()) {
            log.info("Gateway is running on port {}", mySqlProtocolAdapter.getDefaultPort());
        } else {
            log.info("Gateway is not running");
        }
    }
}
