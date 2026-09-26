package com.quickbite.location.api;

import com.quickbite.location.app.RiderLocationService;
import com.quickbite.location.app.RiderLocationService.Candidate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/riders")
@PreAuthorize("hasRole('service')")
public class InternalRiderController {
    private final RiderLocationService service;
    public InternalRiderController(RiderLocationService service) { this.service = service; }

    @GetMapping("/nearest")
    public List<Candidate> nearest(@RequestParam double lat, @RequestParam double lon,
                                   @RequestParam(defaultValue = "5") double radiusKm, @RequestParam(defaultValue = "5") int limit) {
        return service.nearest(lat, lon, radiusKm, Math.min(limit, 20));
    }

    /** 200 {claimed:false} instead of 409: "rider taken" is a normal outcome, not an error. */
    @PostMapping("/{riderId}/claim")
    public Map<String, Boolean> claim(@PathVariable String riderId, @RequestParam String orderId) {
        return Map.of("claimed", service.claim(riderId, orderId));
    }

    @PostMapping("/{riderId}/release")
    public Map<String, Boolean> release(@PathVariable String riderId, @RequestParam String orderId) {
        return Map.of("released", service.release(riderId, orderId));
    }
}