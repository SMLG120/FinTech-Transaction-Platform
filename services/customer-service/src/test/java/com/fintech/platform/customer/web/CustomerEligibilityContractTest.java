package com.fintech.platform.customer.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * The producer side of the card-issuing eligibility contract.
 *
 * <p>card-service reads exactly one field of the profile — {@code kycStatus} as a wire string —
 * before issuing a card. This test deserialises the shared {@code eligibility-response.json}
 * fixture into the real {@link CustomerDtos.CustomerResponse} and pins the contracted values, so
 * a rename of the field or of the {@code APPROVED} value breaks here, on the producer side,
 * rather than as a refused card in production. The consumer side serves the same bytes from a
 * stub server in {@code CustomerServiceEligibilityTest}.
 *
 * <p>No Spring context: the question is purely whether the bytes and the DTO agree.
 */
class CustomerEligibilityContractTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("the shared eligibility fixture deserialises into the real profile DTO")
    void fixtureMatchesProducerDto() throws Exception {
        String fixture = StreamUtils.copyToString(
                new ClassPathResource("contracts/eligibility-response.json").getInputStream(), StandardCharsets.UTF_8);

        CustomerDtos.CustomerResponse response = JSON.readValue(fixture, CustomerDtos.CustomerResponse.class);

        assertThat(response.kycStatus().name()).isEqualTo("APPROVED");
        assertThat(response.id().toString()).isEqualTo("11111111-2222-3333-4444-555555555555");
        assertThat(response.masked()).isFalse();
    }

    @Test
    @DisplayName("a real profile serialises with the contracted kycStatus field")
    void producerDtoCarriesContractedField() throws Exception {
        CustomerDtos.CustomerResponse response = JSON.readValue(
                new ClassPathResource("contracts/eligibility-response.json").getInputStream(),
                CustomerDtos.CustomerResponse.class);

        String reserialised = JSON.writeValueAsString(response);

        assertThat(reserialised).contains("\"kycStatus\":\"APPROVED\"");
    }
}
