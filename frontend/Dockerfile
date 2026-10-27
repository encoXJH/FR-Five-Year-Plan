# 前端多阶段构建：node 构建 + nginx 托管
# 构建：docker build -t metrics-frontend .
# nginx 把 /api 反代到 backend 服务（docker-compose.prod.yml 中服务名为 backend）

FROM node:20-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
