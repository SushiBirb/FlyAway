#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
if [ -f "$DIR/../.env" ]; then
    set -a
    . "$DIR/../.env"
    set +a
fi
exec php -S 0.0.0.0:3000 -t wwwroot -c php.ini