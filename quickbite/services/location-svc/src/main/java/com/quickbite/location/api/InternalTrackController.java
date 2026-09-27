package com.quickbite.location.api;

import com.quickbite.location.app.RiderLocationService;
import com.quickbite.location.app.RiderLocationService.TrackPoint;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Internal only: order-svc calls this with a service token and applies the ownership check itself. */
@RestController
@RequestMapping("/internal/tracks")
@PreAuthorize("hasRole('service')")
public class InternalTrackController {
    private final RiderLocationService service;
    public InternalTrackController(RiderLocationService service) { this.service = service; }

    @GetMapping("/{orderId}")
    public List<TrackPoint> track(@PathVariable String orderId) { return service.track(orderId); }
}
