package com.fintech.platform.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * The producer side of the dispute payment-lookup contract.
 *
 * <p>dispute-service reads three fields of a payment — {@code status}, {@code amount} and {@code
 * currency}, all as wire strings — before opening a case. This test deserialises the shared {@code
 * transaction-view.json} fixture into the real {@link TransactionResponse} and pins those values,
 * so a rename breaks here rather than as a refused dispute in production. The consumer side serves
 * the same bytes from a stub server in dispute-service's {@code TransactionLookupTest}.
 *
 * <p>No Spring context: the question is purely whether the bytes and the DTO agree.
 */
class TransactionViewContractTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("the shared transaction fixture deserialises into the real payment DTO")
    void fixtureMatchesProducerDto() throws Exception {
        String fixture = StreamUtils.copyToString(
                new ClassPathResource("contracts/transaction-view.json").getInputStream(), StandardCharsets.UTF_8);

        TransactionResponse response = JSON.readValue(fixture, TransactionResponse.class);

        assertThat(response.status().name()).isEqualTo("SETTLED");
        assertThat(response.amount()).isEqualTo("25.00");
        assertThat(response.currency()).isEqualTo("GBP");
        assertThat(response.id()).isEqualTo("22222222-3333-4444-5555-666666666666");
    }

    @Test
    @DisplayName("a real payment serialises with the contracted status and figure fields")
    void producerDtoCarriesContractedFields() throws Exception {
        TransactionResponse response = JSON.readValue(
                new ClassPathResource("contracts/transaction-view.json").getInputStream(), TransactionResponse.class);

        String reserialised = JSON.writeValueAsString(response);

        assertThat(reserialised).contains("\"status\":\"SETTLED\"");
        assertThat(reserialised).contains("\"amount\":\"25.00\"");
        assertThat(reserialised).contains("\"currency\":\"GBP\"");
    }
}
