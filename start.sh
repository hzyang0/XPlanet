#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ ! -f xplanet-booking/target/xplanet-booking-2.0.0.jar ]; then
  (cd xplanet-booking && sh mvnw -B package)
fi
exec java -jar xplanet-booking/target/xplanet-booking-2.0.0.jar --spring.profiles.active=demo
