#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1
mkdir -p out && javac -encoding UTF-8 -d out src/*.java || exit 1
export BOLETOS=${BOLETOS:-20} CUPO=${CUPO:-2}
if [ "$1" = "demo" ]; then export BOLETOS=6 CUPO=1; fi
echo "Abre http://localhost:8080 en Chrome/Edge. Ctrl+C detiene todo."
if [ "$1" = "separado" ]; then
  T="-Dtkn=$RANDOM$RANDOM-boleteria"; R=localhost:5100
  trap 'kill 0' EXIT
  java $T -cp out Registro 5100 & sleep 1
  java $T -cp out Sistema "$BOLETOS" 5000 $R &
  java $T -cp out Caja "$CUPO" 5001 C1 $R &
  java $T -cp out Caja "$CUPO" 5002 C2 $R &
  java $T -cp out Compras 5200 $R &
  java $T -cp out Interfaz 8080 $R &
  wait
else
  exec java -cp out Interfaz
fi
