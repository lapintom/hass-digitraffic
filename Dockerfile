ARG BUILD_FROM
FROM $BUILD_FROM

COPY target/hass-digitraffic-1.0.jar /app.jar

# install java
RUN \
    apk add --no-cache \
        openjdk8-jre \
    && rm -fr /tmp/*

CMD [ "/usr/bin/java", "-jar", "/app.jar" ]

