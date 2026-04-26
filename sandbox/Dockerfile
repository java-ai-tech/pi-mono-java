FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1
ENV PATH=/usr/local/bin:/usr/bin:/bin

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash coreutils findutils grep sed gawk \
    curl wget ca-certificates \
    python3 python3-pip python3-venv \
    nodejs npm \
    build-essential make git \
    jq \
    unzip zip tar gzip \
    procps psmisc \
    vim nano less \
 && rm -rf /var/lib/apt/lists/*

RUN pip3 install --no-cache-dir \
    requests \
    pyyaml \
    httpx \
    rich

WORKDIR /workspace

CMD ["/bin/bash"]
