package com.quickbite.payment.psp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CardRulesTest {
    @ParameterizedTest
    @ValueSource(strings = {"4242424242424242", "5555555555554444", "378282246310005", "4000000000000002",
                            "4000000000009995", "4000000000000119", "4000000000001976", "4000000000000341"})
    void allTestCardsPassLuhn(String pan) { assertThat(CardRules.luhnValid(pan)).isTrue(); }

    @ParameterizedTest
    @ValueSource(strings = {"4242424242424241", "1234", "abcd123412341234", ""})
    void invalidNumbersFailLuhn(String pan) { assertThat(CardRules.luhnValid(pan)).isFalse(); }

    @ParameterizedTest
    @CsvSource({"4242424242424242,VISA", "5555555555554444,MASTERCARD", "2223003122003222,MASTERCARD",
                "378282246310005,AMEX", "6521000000000000,RUPAY", "9999999999999995,UNKNOWN"})
    void detectsBrand(String pan, String brand) { assertThat(CardRules.brand(pan)).isEqualTo(brand); }

    @Test
    void normalizesAndMasks() {
        assertThat(CardRules.normalize("4242 4242-4242 4242")).isEqualTo("4242424242424242");
        assertThat(CardRules.mask("4242424242424242")).isEqualTo("****4242");   // the only form allowed in logs
    }
}