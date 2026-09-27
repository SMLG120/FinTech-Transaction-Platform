package com.fintech.platform.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

    @Test
    @DisplayName("exposes the HTTP status as both enum and int for the wire contract")
    void exposesStatus() {
        assertThat(CommonErrorCodes.NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(CommonErrorCodes.NOT_FOUND.httpStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("builds an exception carrying its own default message")
    void buildsException() {
        ApiException exception = CommonErrorCodes.VALIDATION_ERROR.exception();

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCodes.VALIDATION_ERROR);
        assertThat(exception).hasMessage("Request validation failed");
        assertThat(exception.getDetails()).isEmpty();
    }

    @Test
    @DisplayName("keeps caller-supplied detail immutable so a handler cannot mutate a shared map")
    void immutableDetails() {
        ApiException exception = CommonErrorCodes.CONFLICT.exception("Already settled", null);

        assertThatThrownBy(() -> exception.getDetails().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "rejects non SCREAMING_SNAKE_CASE code: \"{0}\"")
    @ValueSource(strings = {"lowercase", "MixedCase", "WITH SPACE", "with-dash", "trailing "})
    @DisplayName("rejects error codes that would break the client contract")
    void rejectsMalformedCodes(String candidate) {
        assertThatThrownBy(() -> ErrorCode.of(candidate, HttpStatus.BAD_REQUEST, "m"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("every shared code is unique, which is what makes the client contract reliable")
    void codesAreUnique() {
        assertThat(CommonErrorCodes.byCode()).hasSameSizeAs(CommonErrorCodes.all());
    }
}
