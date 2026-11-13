# Una imagen para todos los servicios. Por defecto corre todo en un proceso (para hostings con un solo servicio, que dan HTTPS y definen PORT);
# docker-compose.yml usa la misma imagen y arranca cada microservicio en su propio contenedor.
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src ./src
COPY web ./web
RUN mkdir out && javac -encoding UTF-8 -d out src/*.java
ENV BOLETOS=20 CUPO=2
CMD ["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", "out", "Interfaz"]
