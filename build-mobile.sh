#!/bin/bash
set -e

echo "Building production frontend JS..."
sbt frontend/fullLinkJS

echo "Assembling www/ directory..."
rm -rf www
mkdir -p www

cp modules/frontend/index.html www/
cp -r modules/frontend/dist www/
cp -r modules/frontend/icons www/
cp modules/frontend/manifest.json www/
cp modules/frontend/register-sw.js www/
cp modules/frontend/sw.js www/

# Copy production JS
cp modules/frontend/target/scala-3.6.3/frontend-opt/main.js www/main.js

# Update index.html to reference production JS path
sed -i '' 's|target/scala-3.6.3/frontend-fastopt/main.js|main.js|' www/index.html

echo "Syncing with Capacitor..."
npx cap sync

echo "Done. Run 'npx cap open ios' or 'npx cap open android'"
