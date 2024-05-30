#!/bin/bash

# Function to print help message
print_help() {
    echo "Usage: $0 [rebuild]"
    echo "  rebuild   Add --build flag to docker-compose up"
}

# Check for the "rebuild" argument
DOCKER_COMPOSE_CMD="docker-compose up"
if [ "$1" == "rebuild" ]; then
    DOCKER_COMPOSE_CMD="docker-compose up --build"
elif [ -n "$1" ]; then
    print_help
    exit 1
fi

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
  "ruby"
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

echo "Running command: $DOCKER_COMPOSE_CMD"
$DOCKER_COMPOSE_CMD
