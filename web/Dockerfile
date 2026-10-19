FROM m.daocloud.io/docker.io/library/node:22-alpine AS builder
WORKDIR /workspace
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

FROM m.daocloud.io/docker.io/library/nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /workspace/dist /usr/share/nginx/html
EXPOSE 80
