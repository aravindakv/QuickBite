package com.quickbite.catalog.api;

import com.quickbite.catalog.api.Dtos.*;
import com.quickbite.catalog.app.CatalogService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/restaurants")
public class CatalogController {
    private static final CacheControl PUBLIC_60S = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();
    private final CatalogService service;

    public CatalogController(CatalogService service) { this.service = service; }

    /** Cache-Control: public tells NGINX/CDN (and the phone's HTTP cache) that this may be shared for 60 s. */
    @GetMapping
    public ResponseEntity<List<RestaurantSummary>> list(@RequestParam(defaultValue = "blr") String city) {
        return ResponseEntity.ok().cacheControl(PUBLIC_60S).body(service.byCity(city));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RestaurantDetail> detail(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(PUBLIC_60S).body(service.detail(id));
    }

    /** Used by order-svc at checkout. Never cached. */
    @GetMapping("/{id}/menu-items")
    public ResponseEntity<List<MenuItemPrice>> prices(@PathVariable String id, @RequestParam List<String> ids) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.prices(id, ids));
    }

    @PutMapping("/{id}/menu-items/{itemId}/price")
    @PreAuthorize("hasAnyRole('restaurant','admin')")
    public ResponseEntity<Void> updatePrice(@PathVariable String id, @PathVariable String itemId, @RequestBody PriceUpdate body) {
        service.updatePrice(id, itemId, body.price());
        return ResponseEntity.noContent().build();
    }
}