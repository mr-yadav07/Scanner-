FROM python:3.12-slim
ENV DEBIAN_FRONTEND=noninteractive \
    ARDUINO_DIRECTORIES_DATA=/opt/arduino-data \
    ARDUINO_DIRECTORIES_DOWNLOADS=/opt/arduino-downloads \
    ARDUINO_DIRECTORIES_USER=/opt/arduino-user
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl tar unzip xz-utils && rm -rf /var/lib/apt/lists/*
ARG ARDUINO_CLI_VERSION=1.5.1
RUN curl -fsSL "https://downloads.arduino.cc/arduino-cli/arduino-cli_${ARDUINO_CLI_VERSION}_Linux_64bit.tar.gz" -o /tmp/arduino-cli.tar.gz \
    && tar -xzf /tmp/arduino-cli.tar.gz -C /usr/local/bin arduino-cli \
    && rm /tmp/arduino-cli.tar.gz
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
RUN mkdir -p /opt/arduino-data /opt/arduino-downloads /opt/arduino-user/libraries /tmp/espforge
EXPOSE 8080
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8080}"]
