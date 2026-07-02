package com.whosly.gateway.controller;

import com.whosly.gateway.adapter.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final ProtocolAdapter mySqlProtocolAdapter;

    @Autowired
    public GatewayController(ProtocolAdapter mySqlProtocolAdapter) {
        this.mySqlProtocolAdapter = mySqlProtocolAdapter;
    }

    public String startGateway() {
        try {
            if (!mySqlProtocolAdapter.isRunning()) {
                mySqlProtocolAdapter.start();
                return "Gateway started successfully on port " + mySqlProtocolAdapter.getDefaultPort();
            } else {
                return "Gateway is already running";
            }
        } catch (Exception e) {
            log.error("Error starting gateway", e);
            return "Error starting gateway: " + e.getMessage();
        }
    }

    public String stopGateway() {
        try {
            if (mySqlProtocolAdapter.isRunning()) {
                mySqlProtocolAdapter.stop();
                return "Gateway stopped successfully";
            } else {
                return "Gateway is not running";
            }
        } catch (Exception e) {
            log.error("Error stopping gateway", e);
            return "Error stopping gateway: " + e.getMessage();
        }
    }

    public String getStatus() {
        if (mySqlProtocolAdapter.isRunning()) {
            return "Gateway is running on port " + mySqlProtocolAdapter.getDefaultPort();
        } else {
            return "Gateway is not running";
        }
    }
}