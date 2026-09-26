package com.quickbite.location.api;

import com.quickbite.location.app.RiderLocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/riders")
@PreAuthorize("hasRole('rider')")
public class RiderController {
    public record LocationUpdate(double lat, double lon) {}
    private final RiderLocationService service;

    public RiderController(RiderLocationService service) { this.service = service; }

    /** REST alternative to the WebSocket: handy for scripts and load tests. */
    @PostMapping("/me/location")
    public ResponseEntity<Void> update(@AuthenticationPrincipal Jwt jwt, @RequestBody LocationUpdate u) {
        service.update(jwt.getSubject(), u.lat(), u.lon());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        var m = new HashMap<String, Object>();
        m.put("riderId", jwt.getSubject());
        m.put("currentOrder", service.currentClaim(jwt.getSubject()));
        return m;
    }
}