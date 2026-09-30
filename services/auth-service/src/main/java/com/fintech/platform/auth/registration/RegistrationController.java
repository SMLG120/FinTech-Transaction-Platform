package com.fintech.platform.auth.registration;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegistrationController {
    private final RegistrationService registrations;

    public RegistrationController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping("/register")
    public ResponseEntity<RegistrationDtos.RegisterResponse> register(
            @Valid @RequestBody RegistrationDtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrations.register(request));
    }
}
