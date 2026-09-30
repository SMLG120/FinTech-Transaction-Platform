package com.fintech.platform.auth.customer;

import org.springframework.http.HttpStatusCode;

public class CustomerProvisioningException extends RuntimeException {
    private final HttpStatusCode status;

    public CustomerProvisioningException(String message, HttpStatusCode status) {
        super(message);
        this.status = status;
    }

    public CustomerProvisioningException(String message, HttpStatusCode status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatusCode status() {
        return status;
    }
}
