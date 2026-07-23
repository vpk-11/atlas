package com.atlas.dispatchservice.ride;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RideRequest(
        @NotBlank String riderId,
        @NotNull @Valid CoordinateDto pickup,
        @NotNull @Valid CoordinateDto drop
) {
}
