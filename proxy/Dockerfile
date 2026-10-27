# Render virou só um redirecionador: o relay de verdade roda na VPS (Hostinger).
# App, agentes e sites antigos seguem chamando raizestecnologia-relay.onrender.com e caem aqui,
# que repassa tudo (HTTP e WebSocket) pra VPS.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 10000
