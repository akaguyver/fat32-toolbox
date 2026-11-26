#!/bin/bash
# Runs fsck.fat (dosfstools) read-only on the images Fat32FormatterTest leaves in app/build/fat32-images.
# Run after: gradle :app:testDebugUnitTest
set -u
cd "$(dirname "$0")/../app/build/fat32-images" || { echo "No images; run the unit tests first"; exit 1; }
status=0
for img in *.part.img; do
  echo "== $img"
  if fsck.fat -n -v "$img"; then echo "OK: $img"; else echo "FAILED: $img"; status=1; fi
done
exit $status
