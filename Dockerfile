# Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# Run
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Variaveis de ambiente esperadas em runtime (definir no Render / docker run -e):
#   PORT         porta HTTP (default 9090)
#   DATABASE_URL JDBC do Postgres dos usuarios
#                (default dev jdbc:postgresql://127.0.0.1:5433/raizes_relay; em prod: Neon)
#   PGUSER       usuario do Postgres (default postgres)
#   PGPASSWORD   senha do Postgres  (default postgres)
#   JWT_SECRET   segredo base64 HS256 do JWT (troque em prod)
#   ADMIN_EMAIL  email do DONO criado no bootstrap (default admin@raizes.com)
#   ADMIN_SENHA  senha do DONO criado no bootstrap (default raizes123)
ENV PORT=9090
# Limites de memoria da JVM para caber nos 512MB do Render (evita OOM/reinicio):
#  - SerialGC: menor overhead de memoria que o G1 (ideal p/ instancia pequena)
#  - MaxRAMPercentage=45: heap ~230MB, deixando folga p/ metaspace + threads + nativo
#  - Xss512k: reduz o stack por thread (com dezenas de threads, economiza bastante)
# Pode sobrescrever via env JAVA_TOOL_OPTIONS no Render, se precisar.
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:MaxRAMPercentage=45.0 -Xss512k"
EXPOSE 9090
CMD ["java", "-jar", "app.jar"]
