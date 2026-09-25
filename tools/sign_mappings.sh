#!/bin/sh
# Signs affiliate_mappings.json for the app's in-place update mechanism.
#
# The app applies a remote mapping file ONLY if this detached RSA signature
# verifies against the public key embedded in AffiliateConfig. Sign every
# time you change the file and commit the .sig alongside the .json.
#
# Usage:
#   BARELABEL_SIGNING_KEY=/secure/path/mappings-signing.key tools/sign_mappings.sh
#
# The private key must NEVER be committed to the repo.
set -eu
cd "$(dirname "$0")/../app/src/main/assets"
: "${BARELABEL_SIGNING_KEY:?set BARELABEL_SIGNING_KEY to your private key path}"
openssl dgst -sha256 -sign "$BARELABEL_SIGNING_KEY" \
    -out affiliate_mappings.json.sig affiliate_mappings.json
echo "wrote affiliate_mappings.json.sig — commit it alongside affiliate_mappings.json"
