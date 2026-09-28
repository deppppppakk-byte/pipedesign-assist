FROM ubuntu:22.04 AS builder

ARG BUILD_KEY
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates openssl xz-utils python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
COPY windows-source.part00 windows-source.part01 \
     windows-source.part02a windows-source.part02b \
     windows-source.part03a windows-source.part03b windows-source.part03c windows-source.part03d \
     windows-source.part04h0 windows-source.part04h1 windows-source.part04h2 windows-source.part04h3 ./
COPY reconstruct-windows.sh ./
RUN chmod +x reconstruct-windows.sh \
    && ./reconstruct-windows.sh \
    && tar -C /work/src -czf /work/NexControl-v1.1.0-windows-source.tar.gz .

FROM python:3.12-alpine
WORKDIR /srv
COPY --from=builder /work/NexControl-v1.1.0-windows-source.tar.gz /srv/
RUN sha256sum NexControl-v1.1.0-windows-source.tar.gz > NexControl-v1.1.0-windows-source.tar.gz.sha256
EXPOSE 8080
CMD ["sh", "-c", "python -m http.server ${PORT:-8080} --bind 0.0.0.0"]
