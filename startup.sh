#!/bin/bash

# Create Docker volume if it doesn't exist
VOLUME_NAME="engine"
if docker volume inspect "$VOLUME_NAME" > /dev/null 2>&1; then
  echo "Docker volume '$VOLUME_NAME' already exists."
else
  echo "Creating Docker volume '$VOLUME_NAME'."
  docker volume create "$VOLUME_NAME"
fi

# Docker images to be pulled
DOCKER_IMAGES=(
  "openjdk:17"
  "python"
  "node"
)

# Pull Docker images in parallel if they don't already exist
for IMAGE in "${DOCKER_IMAGES[@]}"; do
  if docker image inspect "$IMAGE" > /dev/null 2>&1; then
    echo "Docker image '$IMAGE' already exists."
  else
    echo "Pulling Docker image '$IMAGE' in the background."
    docker pull "$IMAGE" &
  fi
done

# Wait for all background jobs to complete
wait
echo "All Docker images pulled."

echo "Running docker-compose up."

docker-compose up
