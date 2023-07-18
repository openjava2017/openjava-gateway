package org.openjava.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayController {
    @GetMapping("/fallback")
    public String fallback() {
        return "{\"code\": 202, \"message\": \"fallback: 触发熔断\"}" ;
    }

    @GetMapping("/reply")
    public String reply() {
        return "{\"code\": 200, \"message\": \"来源于Controller\"}" ;
    }
}