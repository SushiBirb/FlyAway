#!/bin/bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$DIR/build/classes"
echo "Compiling FlyAway Dedicated Server..."
javac -d "$DIR/build/classes" -cp "$DIR/lib/*" "$DIR/src/net/eastern/FlyAway/Main.java" "$DIR"/src/net/eastern/FlyAway/*/*.java
if [ -f "$DIR/../.env" ]; then
    set -a
    source "$DIR/../.env"
    set +a
fi
echo "Starting FlyAway Dedicated Server on port 8000..."
exec java -cp "$DIR/build/classes:$DIR/res:$DIR/lib/*" net.eastern.FlyAway.Main
