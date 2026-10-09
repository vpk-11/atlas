package com.atlas.tripservice.trip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByTripId(String tripId);

    Optional<Trip> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query("update Trip t set t.status = :to where t.status = :from and t.createdAt < :cutoff")
    int updateStatusWhereOlderThan(@Param("from") TripStatus from, @Param("to") TripStatus to,
                                   @Param("cutoff") Instant cutoff);
}
