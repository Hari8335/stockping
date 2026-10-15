FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm test && npm run build

FROM node:24-alpine AS runtime
ENV NODE_ENV=development
WORKDIR /app
COPY --from=build --chown=node:node /app /app
USER node
EXPOSE 5173
CMD ["npm", "run", "dev"]

