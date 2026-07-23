package com.atlas.dispatchservice.osrm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OsrmRouteResponse(List<Route> routes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(double distance, double duration) {
    }
}
