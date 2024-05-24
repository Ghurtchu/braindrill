# Use an official OpenJDK runtime as a parent image
FROM hseeberger/scala-sbt:17.0.2_1.6.2_3.1.1

# Update the repository sources list
RUN apt-get update

# python runtime
RUN apt-get install -y python3

# javascript runtime
ENV NODE_VERSION=16.13.0
RUN apt install -y curl
RUN curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.0/install.sh | bash
ENV NVM_DIR=/root/.nvm
RUN . "$NVM_DIR/nvm.sh" && nvm install ${NODE_VERSION}
RUN . "$NVM_DIR/nvm.sh" && nvm use v${NODE_VERSION}
RUN . "$NVM_DIR/nvm.sh" && nvm alias default v${NODE_VERSION}
ENV PATH="/root/.nvm/versions/node/v${NODE_VERSION}/bin/:${PATH}"
RUN node --version
RUN npm --version

# Set the working directory to /app
WORKDIR /app

# Copy the current directory contents into the container at /app
COPY . /app

# Build the Scala project
RUN sbt clean assembly

# Run application when the container launches
ENTRYPOINT ["java", "-jar", "target/scala-3.4.1/braindrill.jar"]