# Use an official OpenJDK runtime as a parent image
FROM hseeberger/scala-sbt:17.0.2_1.6.2_3.1.1

# Update the repository sources list
# Update the repository sources list and install required packages
RUN apt-get update && \
    apt-get install -y \
    docker.io

# Set the working directory to /app
WORKDIR /app

# Copy the current directory contents into the container at /app
COPY . /app

# Build the Scala project
RUN sbt clean assembly

# Run application when the container launches
ENTRYPOINT ["java", "-jar", "target/scala-3.4.1/braindrill.jar"]