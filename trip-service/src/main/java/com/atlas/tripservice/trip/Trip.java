package com.atlas.tripservice.trip;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "trips")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", unique = true)
    private String tripId;

    @Column(name = "rider_id", nullable = false)
    private String riderId;

    @Column(name = "driver_id")
    private String driverId;

    @Column(name = "pickup_lat", nullable = false)
    private double pickupLat;

    @Column(name = "pickup_lng", nullable = false)
    private double pickupLng;

    @Column(name = "drop_lat", nullable = false)
    private double dropLat;

    @Column(name = "drop_lng", nullable = false)
    private double dropLng;

    @Column(name = "price", nullable = false)
    private double price;

    @Column(name = "distance_km", nullable = false)
    private double distanceKm;

    @Column(name = "duration_minutes", nullable = false)
    private double durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "distance_source")
    private DistanceSource distanceSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TripStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Trip() {
    }

    public Trip(String riderId, String driverId, double pickupLat, double pickupLng,
                double dropLat, double dropLng, double price, double distanceKm,
                double durationMinutes, DistanceSource distanceSource, TripStatus status) {
        this.riderId = riderId;
        this.driverId = driverId;
        this.pickupLat = pickupLat;
        this.pickupLng = pickupLng;
        this.dropLat = dropLat;
        this.dropLng = dropLng;
        this.price = price;
        this.distanceKm = distanceKm;
        this.durationMinutes = durationMinutes;
        this.distanceSource = distanceSource;
        this.status = status;
        this.createdAt = Instant.now();
    }

    @PostPersist
    private void assignTripId() {
        this.tripId = String.format("T-%08d", id);
    }

    public Long getId() {
        return id;
    }

    public String getTripId() {
        return tripId;
    }

    public String getRiderId() {
        return riderId;
    }

    public String getDriverId() {
        return driverId;
    }

    public void setDriverId(String driverId) {
        this.driverId = driverId;
    }

    public double getPickupLat() {
        return pickupLat;
    }

    public double getPickupLng() {
        return pickupLng;
    }

    public double getDropLat() {
        return dropLat;
    }

    public double getDropLng() {
        return dropLng;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public double getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(double durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public DistanceSource getDistanceSource() {
        return distanceSource;
    }

    public void setDistanceSource(DistanceSource distanceSource) {
        this.distanceSource = distanceSource;
    }

    public TripStatus getStatus() {
        return status;
    }

    public void setStatus(TripStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
