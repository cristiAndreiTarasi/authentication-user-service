# Use the official Gradle image with JDK 19 as the base image for the build stage
FROM gradle:7-jdk19 AS build

# Copy the project files to the container, setting the owner to gradle
COPY --chown=gradle:gradle . /home/gradle/src

# Set the working directory inside the container
WORKDIR /home/gradle/src

# Run the Gradle build command to create a fat JAR file
RUN gradle buildFatJar --no-daemon

# Use the official OpenJDK 19 slim image as the base image for the runtime stage
FROM openjdk:19-jdk-slim

# Expose port 8081 on the container
EXPOSE 8081:8081

# Install psql client
RUN apt-get update && apt-get install -y postgresql-client && rm -rf /var/lib/apt/lists/*

# Create a directory for the application inside the container
RUN mkdir /app

# Copy the built JAR file from the build stage to the application directory
COPY --from=build /home/gradle/src/build/libs/*.jar /app/*.jar

# Set the entry point to run the JAR file using the java -jar command
ENTRYPOINT ["java", "-jar", "/app/*.jar"]
