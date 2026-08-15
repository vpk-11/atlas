package com.atlas.dispatchservice.driver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "drivers")
public class Driver {

    @Id
    @Column(name = "driver_id")
    private String driverId;

    @Column(name = "current_lat", nullable = false)
    private double currentLat;

    @Column(name = "current_lng", nullable = false)
    private double currentLng;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DriverStatus status;

    @Column(name = "estimated_free_at")
    private Instant estimatedFreeAt;

    @Column(name = "destination_lat")
    private Double destinationLat;

    @Column(name = "destination_lng")
    private Double destinationLng;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Driver() {
    }

    public Driver(String driverId, double currentLat, double currentLng, DriverStatus status) {
        this.driverId = driverId;
        this.currentLat = currentLat;
        this.currentLng = currentLng;
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getDriverId() {
        return driverId;
    }

    public double getCurrentLat() {
        return currentLat;
    }

    public double getCurrentLng() {
        return currentLng;
    }

    public void setCurrentLocation(double lat, double lng) {
        this.currentLat = lat;
        this.currentLng = lng;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public DriverStatus getStatus() {
        return status;
    }

    public void setStatus(DriverStatus status) {
        this.status = status;
    }

    public Instant getEstimatedFreeAt() {
        return estimatedFreeAt;
    }

    public void setEstimatedFreeAt(Instant estimatedFreeAt) {
        this.estimatedFreeAt = estimatedFreeAt;
    }

    public Double getDestinationLat() {
        return destinationLat;
    }

    public Double getDestinationLng() {
        return destinationLng;
    }

    public void setDestination(Double lat, Double lng) {
        this.destinationLat = lat;
        this.destinationLng = lng;
    }
}
