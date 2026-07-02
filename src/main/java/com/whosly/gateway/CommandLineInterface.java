package com.whosly.gateway;

import com.whosly.gateway.adapter.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Scanner;

@Component
public class CommandLineInterface implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandLineInterface.class);

    private final ProtocolAdapter mySqlProtocolAdapter;

    @Autowired
    public CommandLineInterface(ProtocolAdapter mySqlProtocolAdapter) {
        this.mySqlProtocolAdapter = mySqlProtocolAdapter;
    }

    @Override
    public void run(String... args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        log.info("Multi-Protocol Database Gateway CLI");
        log.info("Commands: start, stop, status, quit");

        while (running) {
            System.out.print("> ");
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