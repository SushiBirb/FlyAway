#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
exec php -S 0.0.0.0:3000 -t wwwroot -c php.ini