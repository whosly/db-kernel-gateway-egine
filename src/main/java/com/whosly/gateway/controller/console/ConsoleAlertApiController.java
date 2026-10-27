package com.whosly.gateway.controller.console;

import static com.whosly.gateway.console.security.ConsolePermission.*;

import com.whosly.gateway.console.observe.AlertEvaluationService;
import com.whosly.gateway.console.observe.AlertEvaluationService.ThresholdInput;
import com.whosly.gateway.console.observe.AlertNotificationService;
import com.whosly.gateway.console.observe.AlertNotificationService.ChannelInput;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/** Alert thresholds + channels + active alerts under /console/api/v1. */
@RestController
@RequestMapping("/console/api/v1")
public class ConsoleAlertApiController {

    private final AlertEvaluationService alerts;
    private final AlertNotificationService notifications;

    public ConsoleAlertApiController(AlertEvaluationService alerts,
                                     AlertNotificationService notifications) {
        this.alerts = alerts;
        this.notifications = notifications;
    }

    @GetMapping("/alerts/thresholds")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_READ + "')")
    public Map<String, Object> listThresholds() {
        return alerts.listThresholds();
    }

    @GetMapping("/alerts/thresholds/{id}")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_READ + "')")
    public Map<String, Object> getThreshold(@PathVariable("id") String id) {
        return alerts.getThreshold(id);
    }

    @PostMapping("/alerts/thresholds")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_WRITE + "')")
    public ResponseEntity<Map<String, Object>> createThreshold(@RequestBody AlertThresholdBody body) {
        Map<String, Object> created = alerts.create(toInput(body));
        String id = String.valueOf(created.get("id"));
        return ResponseEntity.created(URI.create("/console/api/v1/alerts/thresholds/" + id)).body(created);
    }

    @PutMapping("/alerts/thresholds/{id}")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_WRITE + "')")
    public Map<String, Object> updateThreshold(@PathVariable("id") String id,
                                               @RequestBody AlertThresholdBody body) {
        return alerts.update(id, toInput(body));
    }

    @DeleteMapping("/alerts/thresholds/{id}")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_WRITE + "')")
    public Map<String, Object> deleteThreshold(@PathVariable("id") String id) {
        return alerts.delete(id);
    }

    @GetMapping({"/alerts/active", "/alerts"})
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_READ + "')")
    public Map<String, Object> activeAlerts(
            @RequestParam(value = "refresh", required = false, defaultValue = "true") boolean refresh) {
        return alerts.listActive(refresh);
    }

    @GetMapping("/alerts/channels")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_READ + "')")
    public Map<String, Object> listChannels() {
        return notifications.listChannels();
    }

    @GetMapping("/alerts/channels/{id}")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_READ + "')")
    public Map<String, Object> getChannel(@PathVariable("id") String id) {
        return notifications.getChannel(id);
    }

    @PostMapping("/alerts/channels")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_WRITE + "')")
    public ResponseEntity<Map<String, Object>> createChannel(@RequestBody AlertChannelBody body) {
        Map<String, Object> created = notifications.create(toChannelInput(body));
        String id = String.valueOf(created.get("id"));
        return ResponseEntity.created(URI.create("/console/api/v1/alerts/channels/" + id)).body(created);
    }

    @PutMapping("/alerts/channels/{id}")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_WRITE + "')")
    public Map<String, Object> updateChannel(@PathVariable("id") String id,
                                             @RequestBody AlertChannelBody body) {
        return notifications.update(id, toChannelInput(body));
    }

    @DeleteMapping("/alerts/channels/{id}")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_WRITE + "')")
    public Map<String, Object> deleteChannel(@PathVariable("id") String id) {
        return notifications.delete(id);
    }

    /** Self-check delivery for one configured channel. */
    @PostMapping("/alerts/channels/{id}/test")
    @PreAuthorize("@consoleAuthz.has('" + ALERTS_WRITE + "')")
    public Map<String, Object> testChannel(@PathVariable("id") String id) {
        return notifications.testChannel(id);
    }

    private static ThresholdInput toInput(AlertThresholdBody body) {
        if (body == null) {
            return null;
        }
        return new ThresholdInput(
                body.name(),
                body.metricKey(),
                body.comparator(),
                body.thresholdValue(),
                body.windowSeconds(),
                body.instanceId(),
                body.enabled(),
                body.severity());
    }

    private static ChannelInput toChannelInput(AlertChannelBody body) {
        if (body == null) {
            return null;
        }
        return new ChannelInput(
                body.name(),
                body.type(),
                body.enabled(),
                body.minSeverity(),
                body.cooldownSeconds(),
                body.config());
    }

    public record AlertThresholdBody(
            String name,
            String metricKey,
            String comparator,
            Double thresholdValue,
            Integer windowSeconds,
            String instanceId,
            Boolean enabled,
            String severity
    ) {
    }

    public record AlertChannelBody(
            String name,
            String type,
            Boolean enabled,
            String minSeverity,
            Integer cooldownSeconds,
            Map<String, Object> config
    ) {
    }
}
