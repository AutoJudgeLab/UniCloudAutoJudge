FROM node:22-bookworm-slim

RUN npm install --global typescript@5.8.3 \
    && npm install --prefix /opt/types --ignore-scripts @types/node@22.15.3 \
    && npm cache clean --force
