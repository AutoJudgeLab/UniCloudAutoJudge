FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY apps/judge-web/package*.json ./
RUN npm ci
COPY apps/judge-web/ ./
RUN npm run build

FROM caddy:2-alpine
COPY infrastructure/docker/judge-web/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
ENV JUDGE_PUBLIC_ADDRESS=http://localhost:8088
ENV JUDGE_API_UPSTREAM=judge-api:8080
EXPOSE 8088 80 443
