package com.whosly.gateway.controller.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the Vue SPA under {@code /console/} (built assets in {@code static/console/}).
 *
 * <p>History-mode routes without a file extension forward to {@code index.html}.
 * {@code /console/api/*} stays on {@link ConsoleApiController}; hashed assets under
 * {@code /console/assets/*} are served by Spring's resource handler.</p>
 */
@Controller
public class ConsolePageController {

    @GetMapping({"/console", "/console/"})
    public String consoleRoot() {
        return "forward:/console/index.html";
    }

    /** Single-segment SPA routes (no dot → not a static file). */
    @GetMapping("/console/{path:[^\\.]+}")
    public String consoleSpa(@PathVariable("path") String path) {
        if ("api".equals(path)) {
            // Should not hit here; API is a RestController. Defensive.
            return "forward:/console/index.html";
        }
        return "forward:/console/index.html";
    }
}
