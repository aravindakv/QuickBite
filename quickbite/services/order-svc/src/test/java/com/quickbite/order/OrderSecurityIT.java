package com.quickbite.order;

import com.quickbite.order.client.Clients.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"quickbite.dispatch.interval-ms=3600000"})
@Import(TestContainersConfig.class)
class OrderSecurityIT {
    @MockitoBean CatalogClient catalog;
    @MockitoBean LocationClient location;
    @Autowired WebApplicationContext ctx;
    MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build(); }

    static final String BODY = """
        {"restaurantId":"r1","items":[{"menuItemId":"i1","quantity":1}],"deliveryLat":12.9,"deliveryLon":77.6}""";

    @Test void noTokenIs401() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test void riderCannotPlaceOrders() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                .with(jwt().jwt(j -> j.subject("bob")).authorities(new SimpleGrantedAuthority("ROLE_rider"))))
           .andExpect(status().isForbidden());
    }

    @Test void invalidPayloadIs400() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{\"restaurantId\":\"\",\"items\":[]}")
                .with(jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("ROLE_customer"))))
           .andExpect(status().isBadRequest());
    }

    @Test void adminDebugEndpointNeedsAdmin() throws Exception {
        mvc.perform(post("/api/orders/admin/debug/hang").param("on", "false")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_customer"))))
           .andExpect(status().isForbidden());
    }
}