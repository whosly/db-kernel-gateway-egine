package com.whosly.gateway.controller.console;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the built-in console UI entry (static resources under {@code /console/}).
 */
@Controller
public class ConsolePageController {

    @GetMapping({"/console", "/console/"})
    public String consoleIndex() {
        return "redirect:/console/index.html";
    }
}
