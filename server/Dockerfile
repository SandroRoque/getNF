FROM alpine:3.22

ARG PB_VERSION=0.40.5
ARG PB_ZIP=pocketbase_${PB_VERSION}_linux_amd64.zip
WORKDIR /tmp
RUN apk add --no-cache ca-certificates unzip wget \
 && wget -q "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/${PB_ZIP}" \
 && wget -q "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}/checksums.txt" \
 && grep " ${PB_ZIP}$" checksums.txt | sha256sum -c - \
 && unzip -q "${PB_ZIP}" -d /pb && rm "${PB_ZIP}" checksums.txt
WORKDIR /pb

EXPOSE 8090
# pb_data (db + uploaded files) is a volume; migrations and hooks live in git.
CMD ["/pb/pocketbase", "serve", "--http=0.0.0.0:8090", "--dir=/pb/pb_data", "--migrationsDir=/pb/pb_migrations", "--hooksDir=/pb/pb_hooks"]
