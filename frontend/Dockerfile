FROM node:20-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm install
RUN npm install @vitejs/plugin-react@4.3.4 --save-dev   # ← pin to vite 5 compatible version
COPY . .
RUN npm run build

FROM nginx:alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]