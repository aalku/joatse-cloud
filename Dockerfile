# Multi-stage build for Joatse Cloud Service
FROM amazoncorretto:17 AS builder

# Install Maven
RUN yum update -y && \
    yum install -y wget tar gzip && \
    cd /opt && \
    wget https://archive.apache.org/dist/maven/maven-3/3.9.5/binaries/apache-maven-3.9.5-bin.tar.gz && \
    tar -xzf apache-maven-3.9.5-bin.tar.gz && \
    ln -s apache-maven-3.9.5 maven && \
    rm apache-maven-3.9.5-bin.tar.gz && \
    yum clean all

# Set Maven environment
ENV MAVEN_HOME=/opt/maven
ENV PATH=$PATH:$MAVEN_HOME/bin

# Set working directory
WORKDIR /app

# Copy pom.xml for dependency caching
COPY pom.xml ./

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Runtime stage
FROM amazoncorretto:17

# Add metadata
LABEL maintainer="Joatse Cloud"
LABEL description="Joatse Cloud Service - Tunnel Router"

# Set working directory
WORKDIR /app

# Install tools and create non-root user for security
RUN yum install -y shadow-utils curl && \
    groupadd -g 1000 joatse && \
    useradd -r -u 1000 -g joatse joatse && \
    mkdir -p /app/data && \
    chown -R joatse:joatse /app && \
    yum clean all

# Copy JAR from builder stage
COPY --from=builder /app/target/joatse-cloud-*.jar /app/joatse-cloud.jar

# Change ownership and switch to non-root user
RUN chown joatse:joatse /app/joatse-cloud.jar
USER joatse

# Expose ports
EXPOSE 9011 9012-9100 9101-9106 9107-9110

# Health check endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:9011/actuator/health || exit 1

# Set JVM options for container
ENV JAVA_OPTS="-Xmx512m -Xms256m -Djava.security.egd=file:/dev/./urandom -Dlogging.level.org.springframework=DEBUG -Dlogging.level.org.aalku=DEBUG"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/joatse-cloud.jar"]