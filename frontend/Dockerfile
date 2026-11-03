# 前端镜像（#24，决策 036）：先在 Node 镜像里打包，再把静态文件放进 Nginx。
# Nginx 同时把 /api 转给后端，浏览器只访问这一个地址。由仓库根目录的 compose.yaml 构建。

FROM node:24.22.2-alpine AS build
WORKDIR /src
# 先只复制依赖描述并安装：只改代码时这一层可以复用；npm 的下载缓存放在构建缓存里
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

FROM nginx:1.30.6-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
