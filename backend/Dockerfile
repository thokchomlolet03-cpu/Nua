FROM node:22-slim

# Install ffmpeg for audio extraction
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install dependencies
COPY package*.json ./
RUN npm ci --only=production

# Copy compiled output
COPY dist/ ./dist/

EXPOSE 8080
ENV PORT=8080

CMD ["node", "dist/index.js"]
