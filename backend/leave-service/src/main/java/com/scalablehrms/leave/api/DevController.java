package com.scalablehrms.leave.api;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

// Only for local development: not available unless SPRING_PROFILES_ACTIVE=dev.
@Profile("dev")
@RestController
@RequestMapping("/api/dev")
public class DevController {
    @PostMapping("/rate-test")
    public Map<String,String> rateTest() { return Map.of("result", "accepted"); }
}
