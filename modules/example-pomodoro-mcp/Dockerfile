FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/scala-3.3.4/pomodoro-mcp /app/pomodoro-mcp

EXPOSE 25000

ENTRYPOINT ["/app/pomodoro-mcp"]
