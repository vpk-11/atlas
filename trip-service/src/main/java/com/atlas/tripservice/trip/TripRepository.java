package com.atlas.tripservice.trip;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByTripId(String tripId);

    Optional<Trip> findByRiderIdAndIdempotencyKey(String riderId, String idempotencyKey);

    // Only the current claim holder can move an unsettled row: REQUESTED to anything, or SYSTEM_ERROR to a settled
    // outcome (a late finalize after the sweep). MATCHED, FAILED_NO_MATCH and CANCELLED are
    // never overwritten.
    @Modifying
    @Transactional
    @Query("update Trip t set t.driverId = :driverId, t.price = :price, t.distanceKm = :distanceKm, "
            + "t.durationMinutes = :durationMinutes, t.distanceSource = :distanceSource, t.status = :status "
            + "where t.tripId = :tripId and t.claimToken = :claimToken "
            + "and (t.status = :requested or (t.status = :systemError and :status <> :systemError))")
    int updateOutcome(@Param("tripId") String tripId, @Param("claimToken") String claimToken,
                      @Param("driverId") String driverId,
                      @Param("price") double price, @Param("distanceKm") double distanceKm,
                      @Param("durationMinutes") double durationMinutes,
                      @Param("distanceSource") DistanceSource distanceSource,
                      @Param("status") TripStatus status,
                      @Param("requested") TripStatus requested, @Param("systemError") TripStatus systemError);

    // Takes over an unsettled row for a replay. Wins only if the row is SYSTEM_ERROR, or REQUESTED
    // with an expired lease, so a replay racing a live original loses and gets resume_granted=false.
    // Winning replaces the claim token, which fences off every later write from the previous owner.
    @Modifying
    @Transactional
    @Query("update Trip t set t.status = :requested, t.claimedAt = :now, t.claimToken = :newToken "
            + "where t.tripId = :tripId and (t.status = :systemError or (t.status = :requested and t.claimedAt < :leaseCutoff))")
    int claimForResume(@Param("tripId") String tripId, @Param("requested") TripStatus requested,
                       @Param("systemError") TripStatus systemError, @Param("now") Instant now,
                       @Param("newToken") String newToken, @Param("leaseCutoff") Instant leaseCutoff);

    @Modifying
    @Transactional
    @Query("update Trip t set t.status = :to where t.status = :from and t.claimedAt < :cutoff")
    int updateStatusWhereOlderThan(@Param("from") TripStatus from, @Param("to") TripStatus to,
                                   @Param("cutoff") Instant cutoff);
}
