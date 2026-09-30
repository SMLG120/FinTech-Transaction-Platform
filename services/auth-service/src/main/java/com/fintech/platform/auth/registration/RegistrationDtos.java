package com.fintech.platform.auth.registration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public final class RegistrationDtos {
    private RegistrationDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Za-z]{2}") String nationality,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 32) String phone,
            @NotNull @Valid Address address) {}

    public record Address(
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Za-z]{2}") String country) {}

    public record RegisterResponse(UUID customerId, String message) {}
}
