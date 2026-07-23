package com.atlas.dispatchservice.ride;

import jakarta.validation.constraints.NotNull;

public record CoordinateDto(
        @NotNull Double lat,
        @NotNull Double lng
) {
}
