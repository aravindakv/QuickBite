
package com.quickbite.order;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import com.quickbite.order.domain.Events;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCreatedContractTest {

    @Test
    void producerMatchesContract() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        var produced = json.readTree(json.writeValueAsString(new Events.OrderCreated(
                "7322148834123456789", "5f1c-alice", "r1", new BigDecimal("300.00"), "INR", 12.9279, 77.6271,
                "pm_3f9a0c1d2e4b5a6978c1d2e3")));
        var contract = json.readTree(Files.readString(Path.of("../../contracts/order.created.v1.json")));

        assertThat(produced.propertyNames()).containsExactlyInAnyOrderElementsOf(contract.propertyNames());
    }
}