
package com.quickbite.payment;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import com.quickbite.payment.domain.Events;

import static org.assertj.core.api.Assertions.assertThat;

class OrderCreatedContractTest {

    @Test
    void consumerCanReadContract() throws Exception {
        var e = JsonMapper.builder().build().readValue(
                Files.readString(Path.of("../../contracts/order.created.v1.json")), Events.OrderCreated.class);
        assertThat(e.amount()).isEqualByComparingTo("300.00");
        assertThat(e.orderId()).isEqualTo("7322148834123456789");
        assertThat(e.paymentMethodId()).startsWith("pm_"); // a token id, never card data
    }
}