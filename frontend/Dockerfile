FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
# 운영 번들의 API 주소. 빈 문자열이 기본이며, 그러면 config.ts의 `?? 'http://localhost:8080'`
# 폴백이 걸리지 않는다(빈 문자열은 nullish가 아니다) → 요청이 상대경로가 되어 같은 오리진의
# nginx가 /api/ 를 backend로 프록시한다. CORS 자체가 발생하지 않는 구성이다.
# 앱(Capacitor) 번들만은 예외로 실제 도메인을 넣어야 한다 — 기기 안에서 실행되므로 상대경로가
# 파일 오리진을 가리킨다. 그때는 --build-arg VITE_API_BASE=https://<도메인> 으로 덮는다.
ARG VITE_API_BASE=""
ENV VITE_API_BASE=$VITE_API_BASE
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
