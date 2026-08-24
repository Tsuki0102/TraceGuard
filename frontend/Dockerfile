# 构建阶段（OPS-12：统一 Node 20 LTS，与 CI/部署文档口径一致）
FROM node:20-alpine AS build
WORKDIR /app
# OPS-12：npm registry 参数化（国内环境可用 --build-arg NPM_REGISTRY=https://registry.npmmirror.com）
ARG NPM_REGISTRY=https://registry.npmjs.org
COPY package*.json ./
RUN npm ci --registry=$NPM_REGISTRY
COPY . .
RUN npm run build

# 运行阶段
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
