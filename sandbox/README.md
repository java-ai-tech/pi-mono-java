# pi-agent-sandbox Docker image

Pre-built Docker image used by `DockerIsolatedBackend` to run user/agent commands in an isolated sandbox.

## Build

    bash sandbox/build.sh

This produces the `pi-agent-sandbox:latest` image locally.

## Tools included

- bash, coreutils, findutils, grep, sed, gawk
- curl, wget, ca-certificates
- python3, pip (pre-installed: requests, pyyaml, httpx, rich), node, npm
- build-essential, make, git
- jq, unzip, zip, tar, gzip
- procps, psmisc, vim, nano, less

## Runtime configuration

`DockerIsolatedBackend` reads `pi.execution.docker.*` from `application.yml`.
Key knobs:

| key | default | meaning |
|---|---|---|
| `image` | `pi-agent-sandbox:latest` | Image to run |
| `network` | `bridge` | Set to `none` to disable networking |
| `read-only-rootfs` | `true` | Whether to add `--read-only` |
| `tmpfs.tmp` / `var-tmp` / `run` | `512m` / `128m` / `64m` | tmpfs sizes |
| `cpu-quota` / `memory-limit` / `pids-limit` | `50000` / `256m` / `100` | resource limits |

## Manual verification

After building the image and starting the server, run a bash tool call that executes:

    python3 -c "import requests; print(requests.__version__)"   # success
    curl -sS https://example.com -o /dev/null && echo ok        # success (bridge)
    echo test > /etc/x                                          # fails (read-only rootfs)
    echo test > /tmp/x && cat /tmp/x                            # success (tmpfs)
    echo test > /workspace/x && cat /workspace/x                # success (volume)

To verify network can be disabled, set `PI_DOCKER_NETWORK=none` and restart; `curl` should now fail while `python3 -c "print(1)"` still works.

## Rollback

To revert to the previous behavior, set:

    PI_DOCKER_IMAGE=ubuntu:22.04
    PI_DOCKER_NETWORK=none
