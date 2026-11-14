FROM --platform=linux/arm64 debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       bash ca-certificates coreutils curl grep gzip sed tar unzip zip xz-utils python3 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* \
    && mkdir -p /root/.arduino15 /workspace /tmp \
    && chmod 1777 /tmp

CMD ["/bin/bash"]
