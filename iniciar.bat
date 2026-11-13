@echo off
chcp 65001 >nul
rem Microservicios de la boleteria.
rem   iniciar.bat          -> un solo proceso con los 6 servicios (cada uno en su puerto), 20 boletos
rem   iniciar.bat demo     -> igual, con 6 boletos y cajas de 1 cliente (la fila se ve rapido)
rem   iniciar.bat separado -> cada servicio en su propia ventana (para apagar uno y ver como reacciona el resto)
set BOLETOS=20
set CUPO=2
if /i "%1"=="demo" (set BOLETOS=6& set CUPO=1)
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java || (echo Error de compilacion. & exit /b 1)
if /i "%1"=="separado" goto separado
start http://localhost:8080
java -cp out Interfaz
exit /b
:separado
set T=-Dtkn=%RANDOM%%RANDOM%-boleteria
start "Registro" java %T% -cp out Registro 5100
timeout /t 1 >nul
start "Sistema (inventario)" java %T% -cp out Sistema %BOLETOS% 5000 localhost:5100
start "Caja C1" java %T% -cp out Caja %CUPO% 5001 C1 localhost:5100
start "Caja C2" java %T% -cp out Caja %CUPO% 5002 C2 localhost:5100
start "Compras" java %T% -cp out Compras 5200 localhost:5100
start "Interfaz (gateway)" java %T% -cp out Interfaz 8080 localhost:5100
timeout /t 3 >nul
start http://localhost:8080
echo Cierra la ventana de un servicio para ver como reacciona el resto.
