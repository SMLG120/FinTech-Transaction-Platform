package com.fintech.platform.auth.registration;

import com.fintech.platform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Public errors for customer account creation. */
public final class RegistrationErrorCodes {

    private RegistrationErrorCodes() {}

    public static final ErrorCode EMAIL_ALREADY_REGISTERED =
            ErrorCode.of(
                    "EMAIL_ALREADY_REGISTERED",
                    HttpStatus.CONFLICT,
                    "An account already exists for that email address. Sign in or use a different email.");
}
