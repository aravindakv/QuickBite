package com.quickbite.payment.psp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class FakePspTest {
    FakePsp psp;

    @BeforeEach
    void setUp() {
        // H2 in-memory DB with the same vault table
        var ds = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        var jdbc = JdbcClient.create(ds);
        jdbc.sql("create table psp_fake_vault(token varchar(64) primary key, brand varchar(20), last4 varchar(4), " +
                 "behavior varchar(30), decline_code varchar(40), created_at timestamp default current_timestamp)").update();
        psp = new FakePsp(jdbc, 0, 0.0);
    }

    @Test void approvesGoodCard() {
        var t = psp.tokenize("4242 4242 4242 4242", 12, 2030, "123");
        assertThat(t.brand()).isEqualTo("VISA");
        assertThat(t.last4()).isEqualTo("4242");
        assertThat(psp.authorize("order-1", t.token(), BigDecimal.TEN).approved()).isTrue();
    }

    @Test void declinesAreReturnedNotThrown() {
        var t = psp.tokenize("4000000000009995", 12, 2030, "123");
        var r = psp.authorize("order-2", t.token(), BigDecimal.TEN);
        assertThat(r.approved()).isFalse();
        assertThat(r.declineCode()).isEqualTo("insufficient_funds");
    }

    @Test void processingErrorIsTransient() {
        var t = psp.tokenize("4000000000000119", 12, 2030, "123");
        // (no Spring proxy here -> no @Retryable; a single attempt throws)
        assertThatThrownBy(() -> psp.authorize("order-3", t.token(), BigDecimal.TEN))
            .isInstanceOf(FakePsp.PspTransientException.class);
    }

    @Test void sameIdempotencyKeySameResult() {
        var t = psp.tokenize("4242424242424242", 12, 2030, "123");
        var a = psp.authorize("order-4", t.token(), BigDecimal.TEN);
        var b = psp.authorize("order-4", t.token(), BigDecimal.TEN);
        assertThat(b.reference()).isEqualTo(a.reference());      // no double charge on retry
    }

    @Test void validationErrors() {
        assertThatIllegalArgumentException().isThrownBy(() -> psp.tokenize("4242424242424241", 12, 2030, "123"))
            .withMessageContaining("Invalid card number");
        assertThatIllegalArgumentException().isThrownBy(() -> psp.tokenize("4242424242424242", 1, 2020, "123"))
            .withMessageContaining("expired");
        assertThatIllegalArgumentException().isThrownBy(() -> psp.tokenize("378282246310005", 12, 2030, "123"))
            .withMessageContaining("4 digits");                   // Amex needs a 4-digit CVC
    }
}