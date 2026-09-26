package com.quickbite.order.api;

import com.quickbite.order.app.*;
import com.quickbite.order.domain.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    private final OrderRepository repo;
    private final DebugSwitch debug;

    public OrderController(OrderService service, OrderRepository repo, DebugSwitch debug) {
        this.service = service; this.repo = repo; this.debug = debug;
    }

    @PostMapping
    @PreAuthorize("hasRole('customer')")
    public ResponseEntity<OrderResponse> place(@AuthenticationPrincipal Jwt jwt,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody PlaceOrderRequest req) {
        Order o = service.place(jwt.getSubject(), req, key);
        return ResponseEntity.created(URI.create("/api/orders/" + o.getId())).body(OrderResponse.from(o));
    }

    @GetMapping
    public List<OrderResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return repo.findTop20ByCustomerIdOrderByCreatedAtDesc(jwt.getSubject()).stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        Order o = repo.findById(id).orElseThrow(() -> new NoSuchElementException("order " + id));
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_admin"));
        // Object-level authorization: stops "BOLA" (alice reading bob's order by guessing ids)
        if (!o.visibleTo(jwt.getSubject(), admin)) throw new AccessDeniedException("not your order");
        return OrderResponse.from(o);
    }

    @GetMapping("/rider/active")
    @PreAuthorize("hasRole('rider')")
    public ResponseEntity<OrderResponse> activeForRider(@AuthenticationPrincipal Jwt jwt) {
        return repo.findFirstByRiderIdAndStatusIn(jwt.getSubject(), List.of(OrderStatus.RIDER_ASSIGNED, OrderStatus.PICKED_UP))
                .map(o -> ResponseEntity.ok(OrderResponse.from(o)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/pickup")
    @PreAuthorize("hasRole('rider')")
    public OrderResponse pickup(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return OrderResponse.from(service.riderAction(id, jwt.getSubject(), OrderStatus.PICKED_UP));
    }

    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasRole('rider')")
    public OrderResponse deliver(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return OrderResponse.from(service.riderAction(id, jwt.getSubject(), OrderStatus.DELIVERED));
    }

    /** Chaos helper for file 09/11: freezes the dispatcher loop. */
    @PostMapping("/admin/debug/hang")
    @PreAuthorize("hasRole('admin')")
    public String hang(@RequestParam boolean on) { debug.setHang(on); return "dispatcher hang=" + on; }
}