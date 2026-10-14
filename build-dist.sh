#!/usr/bin/env bash
# Build the plug-and-play distribution zip: target/app-dist.zip
# (Frontend is Thymeleaf — already embedded in the JAR, no separate FE build.)
set -euo pipefail
cd "$(dirname "$0")"

echo ">> [1/3] Building fat JAR (mvn package)..."
mvn -q -DskipTests clean package

# ausy-*-trigger.jar (REQ_AUSY_00058) juga cocok dengan pola ini dan urutannya lebih dulu —
# jadi aplikasinya dipilih dengan menyingkirkan JAR pemicu.
JAR=$(ls target/ausy-*.jar | grep -v -- '-trigger.jar$' | head -1)
TRIGGER=$(ls target/ausy-*-trigger.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { echo "ERROR: JAR tidak terbentuk."; exit 1; }

echo ">> [2/3] Menyusun folder distribusi..."
STAGE=target/app-dist
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp "$JAR" "$STAGE"/
[ -n "$TRIGGER" ] || { echo "ERROR: ausy-trigger.jar tidak terbentuk."; exit 1; }
cp "$TRIGGER" "$STAGE"/ausy-trigger.jar
cp packaging/setup-db.sql packaging/.env.example packaging/start.sh packaging/README.txt packaging/ausy.service packaging/ausy-acctxt.service "$STAGE"/
chmod +x "$STAGE"/start.sh

echo ">> [3/3] Membuat app-dist.zip..."
( cd "$STAGE" && rm -f ../app-dist.zip && zip -rq ../app-dist.zip . )
echo ">> Selesai: $(cd target && pwd)/app-dist.zip"
( cd target && unzip -l app-dist.zip )
