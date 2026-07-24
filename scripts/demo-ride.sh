#!/usr/bin/env bash
# Interactive terminal demo for dispatch-service's POST /rides + cancel flow.
# Requires dispatch-service (and pricing-service, trip-service) already running.
set -euo pipefail

DISPATCH_URL="${DISPATCH_URL:-http://localhost:8080}"

echo "=== Atlas ride demo ==="
echo "Dispatch: $DISPATCH_URL"
echo

read -rp "Rider ID (e.g. R-001): " RIDER_ID
read -rp "Pickup latitude: " PICKUP_LAT
read -rp "Pickup longitude: " PICKUP_LNG
read -rp "Drop latitude: " DROP_LAT
read -rp "Drop longitude: " DROP_LNG

REQUEST_JSON=$(python3 -c "
import json
print(json.dumps({
    'riderId': '$RIDER_ID',
    'pickup': {'lat': $PICKUP_LAT, 'lng': $PICKUP_LNG},
    'drop': {'lat': $DROP_LAT, 'lng': $DROP_LNG}
}))
")

echo
echo "--- Request ---"
echo "$REQUEST_JSON" | python3 -m json.tool

RESPONSE_JSON=$(curl -s -X POST "$DISPATCH_URL/rides" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_JSON")

echo
echo "--- Response ---"
echo "$RESPONSE_JSON" | python3 -m json.tool

STATUS=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status'))")

echo
case "$STATUS" in
  MATCHED)
    DRIVER_ID=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['driverId'])")
    DRIVER_STATUS=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['driverStatus'])")
    PRICE=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['price'])")
    ETA=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['estimatedPickupMinutes'])")
    TRIP_ID=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['tripId'])")
    ETA_NOTE=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin).get('etaNote') or '')")

    echo "Matched! Driver $DRIVER_ID ($DRIVER_STATUS) is ~$ETA min away."
    [ -n "$ETA_NOTE" ] && echo "$ETA_NOTE"
    echo "Price: \$$PRICE"
    echo "Trip ID: $TRIP_ID"
    echo

    read -rp "Cancel this trip? (y/N): " CANCEL_CHOICE
    if [[ "$CANCEL_CHOICE" =~ ^[Yy]$ ]]; then
        CANCEL_RESPONSE=$(curl -s -X POST "$DISPATCH_URL/trips/$TRIP_ID/cancel")
        echo
        echo "--- Cancel response ---"
        echo "$CANCEL_RESPONSE" | python3 -m json.tool
    fi
    ;;
  FAILED_NO_MATCH)
    MESSAGE=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['message'])")
    echo "No match: $MESSAGE"
    ;;
  SYSTEM_ERROR)
    MESSAGE=$(echo "$RESPONSE_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['message'])")
    echo "System error: $MESSAGE"
    ;;
  *)
    echo "Unexpected response, see JSON above."
    ;;
esac
