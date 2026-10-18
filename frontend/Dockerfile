# Build: `vite build` picks up .env.production (Keycloak settings).
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# git: @teeetime/teaz-pro-ui is a GitHub dependency, built by npm on install.
RUN apk add --no-cache git && npm ci
COPY . .
RUN npm run build

FROM nginx:1.28-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
