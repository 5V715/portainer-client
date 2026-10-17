package dev.silas.portainer.core

/**
 * Recreate-planner fixtures, shaped exactly like Docker Engine API v1.48 output
 * (`GET /containers/{id}/json`, `GET /images/{id}/json`). Field names and nesting match
 * what `docker inspect` prints for Docker 28.x.
 */
object RecreateFixtures {
    /**
     * `docker run -d --name /app-web` with: bind `/srv/app/config:/etc/app:ro`, named volume
     * `app-uploads:/uploads`, `--mount type=volume,source=app-cache,target=/cache`, an anonymous
     * volume on `/data` (declared by the image), primary network `frontend` (aliases incl. the
     * short id), extra network `backend` with static IP 172.20.0.10, ports, healthcheck, compose
     * labels, restart policy, caps, a device, log config, extra hosts and DNS.
     */
    const val INSPECT_FULL = """{
  "Id": "3f4e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0",
  "Created": "2026-10-01T08:15:42.123456789Z",
  "Path": "/entrypoint.sh",
  "Args": ["app", "serve"],
  "State": {
    "Status": "running",
    "Running": true,
    "Paused": false,
    "Restarting": false,
    "OOMKilled": false,
    "Dead": false,
    "Pid": 48213,
    "ExitCode": 0,
    "Error": "",
    "StartedAt": "2026-10-01T08:15:43.001122334Z",
    "FinishedAt": "0001-01-01T00:00:00Z",
    "Health": {
      "Status": "healthy",
      "FailingStreak": 0,
      "Log": [
        {"Start": "2026-10-17T09:00:00.000000000Z", "End": "2026-10-17T09:00:00.100000000Z", "ExitCode": 0, "Output": "ok\n"}
      ]
    }
  },
  "Image": "sha256:7d1c3e5f9a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d",
  "ResolvConfPath": "/var/lib/docker/containers/3f4e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0/resolv.conf",
  "HostnamePath": "/var/lib/docker/containers/3f4e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0/hostname",
  "HostsPath": "/var/lib/docker/containers/3f4e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0/hosts",
  "LogPath": "/var/lib/docker/containers/3f4e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0/3f4e5d6c7b8a9f0e1d2c3b4a5968778695a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0-json.log",
  "Name": "/app-web",
  "RestartCount": 0,
  "Driver": "overlay2",
  "Platform": "linux",
  "MountLabel": "",
  "ProcessLabel": "",
  "AppArmorProfile": "docker-default",
  "ExecIDs": null,
  "HostConfig": {
    "Binds": [
      "/srv/app/config:/etc/app:ro",
      "app-uploads:/uploads"
    ],
    "ContainerIDFile": "",
    "LogConfig": {
      "Type": "json-file",
      "Config": {"max-file": "3", "max-size": "10m"}
    },
    "NetworkMode": "frontend",
    "PortBindings": {
      "80/tcp": [{"HostIp": "", "HostPort": "8080"}],
      "9090/tcp": [{"HostIp": "127.0.0.1", "HostPort": "9090"}]
    },
    "RestartPolicy": {"Name": "unless-stopped", "MaximumRetryCount": 0},
    "AutoRemove": false,
    "VolumeDriver": "",
    "VolumesFrom": null,
    "ConsoleSize": [0, 0],
    "CapAdd": ["NET_ADMIN", "SYS_TIME"],
    "CapDrop": ["MKNOD"],
    "CgroupnsMode": "private",
    "Dns": ["1.1.1.1", "9.9.9.9"],
    "DnsOptions": ["ndots:2"],
    "DnsSearch": ["lan"],
    "ExtraHosts": ["db.internal:10.0.0.5", "host.docker.internal:host-gateway"],
    "GroupAdd": null,
    "IpcMode": "private",
    "Cgroup": "",
    "Links": null,
    "OomScoreAdj": 0,
    "PidMode": "",
    "Privileged": false,
    "PublishAllPorts": false,
    "ReadonlyRootfs": false,
    "SecurityOpt": null,
    "UTSMode": "",
    "UsernsMode": "",
    "ShmSize": 67108864,
    "Runtime": "runc",
    "Isolation": "",
    "CpuShares": 0,
    "Memory": 536870912,
    "NanoCpus": 1500000000,
    "CgroupParent": "",
    "BlkioWeight": 0,
    "BlkioWeightDevice": [],
    "BlkioDeviceReadBps": [],
    "BlkioDeviceWriteBps": [],
    "BlkioDeviceReadIOps": [],
    "BlkioDeviceWriteIOps": [],
    "CpuPeriod": 0,
    "CpuQuota": 0,
    "CpuRealtimePeriod": 0,
    "CpuRealtimeRuntime": 0,
    "CpusetCpus": "",
    "CpusetMems": "",
    "Devices": [
      {"PathOnHost": "/dev/ttyUSB0", "PathInContainer": "/dev/ttyUSB0", "CgroupPermissions": "rwm"}
    ],
    "DeviceCgroupRules": null,
    "DeviceRequests": null,
    "MemoryReservation": 0,
    "MemorySwap": 1073741824,
    "MemorySwappiness": null,
    "OomKillDisable": null,
    "PidsLimit": null,
    "Ulimits": [{"Name": "nofile", "Hard": 65536, "Soft": 65536}],
    "CpuCount": 0,
    "CpuPercent": 0,
    "IOMaximumIOps": 0,
    "IOMaximumBandwidth": 0,
    "Mounts": [
      {"Type": "volume", "Source": "app-cache", "Target": "/cache"}
    ],
    "MaskedPaths": [
      "/proc/asound", "/proc/acpi", "/proc/kcore", "/proc/keys", "/proc/latency_stats",
      "/proc/timer_list", "/proc/timer_stats", "/proc/sched_debug", "/proc/scsi", "/sys/firmware", "/sys/devices/virtual/powercap"
    ],
    "ReadonlyPaths": ["/proc/bus", "/proc/fs", "/proc/irq", "/proc/sys", "/proc/sysrq-trigger"]
  },
  "GraphDriver": {
    "Data": {
      "LowerDir": "/var/lib/docker/overlay2/5a1b2c3d4e5f-init/diff",
      "MergedDir": "/var/lib/docker/overlay2/5a1b2c3d4e5f/merged",
      "UpperDir": "/var/lib/docker/overlay2/5a1b2c3d4e5f/diff",
      "WorkDir": "/var/lib/docker/overlay2/5a1b2c3d4e5f/work"
    },
    "Name": "overlay2"
  },
  "Mounts": [
    {
      "Type": "bind",
      "Source": "/srv/app/config",
      "Destination": "/etc/app",
      "Mode": "ro",
      "RW": false,
      "Propagation": "rprivate"
    },
    {
      "Type": "volume",
      "Name": "app-uploads",
      "Source": "/var/lib/docker/volumes/app-uploads/_data",
      "Destination": "/uploads",
      "Driver": "local",
      "Mode": "z",
      "RW": true,
      "Propagation": ""
    },
    {
      "Type": "volume",
      "Name": "app-cache",
      "Source": "/var/lib/docker/volumes/app-cache/_data",
      "Destination": "/cache",
      "Driver": "local",
      "Mode": "z",
      "RW": true,
      "Propagation": ""
    },
    {
      "Type": "volume",
      "Name": "9b1e0d7c4a3f2e8b5c6d1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d",
      "Source": "/var/lib/docker/volumes/9b1e0d7c4a3f2e8b5c6d1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d/_data",
      "Destination": "/data",
      "Driver": "local",
      "Mode": "",
      "RW": true,
      "Propagation": ""
    }
  ],
  "Config": {
    "Hostname": "3f4e5d6c7b8a",
    "Domainname": "",
    "User": "",
    "AttachStdin": false,
    "AttachStdout": false,
    "AttachStderr": false,
    "ExposedPorts": {
      "80/tcp": {},
      "9090/tcp": {}
    },
    "Tty": false,
    "OpenStdin": false,
    "StdinOnce": false,
    "Env": [
      "DB_HOST=db",
      "TZ=Europe/Berlin",
      "LOG_LEVEL=info",
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
      "APP_VERSION=1"
    ],
    "Cmd": ["app", "serve"],
    "Healthcheck": {
      "Test": ["CMD-SHELL", "wget -qO- http://localhost/health || exit 1"],
      "Interval": 30000000000,
      "Timeout": 5000000000,
      "Retries": 3
    },
    "Image": "ghcr.io/example/app:1.0",
    "Volumes": {
      "/data": {}
    },
    "WorkingDir": "/app",
    "Entrypoint": ["/entrypoint.sh"],
    "OnBuild": null,
    "Labels": {
      "com.docker.compose.config-hash": "4c1d2e3f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f",
      "com.docker.compose.container-number": "1",
      "com.docker.compose.oneoff": "False",
      "com.docker.compose.project": "shop",
      "com.docker.compose.project.config_files": "/srv/shop/compose.yaml",
      "com.docker.compose.project.working_dir": "/srv/shop",
      "com.docker.compose.service": "web",
      "com.docker.compose.version": "2.29.7",
      "org.opencontainers.image.title": "app",
      "org.opencontainers.image.version": "1.0",
      "traefik.enable": "true"
    },
    "StopSignal": "SIGQUIT"
  },
  "NetworkSettings": {
    "Bridge": "",
    "SandboxID": "e2a4c6e8f0a1b3c5d7e9f1a3b5c7d9e1f3a5b7c9d1e3f5a7b9c1d3e5f7a9b1c3",
    "SandboxKey": "/var/run/docker/netns/e2a4c6e8f0a1",
    "Ports": {
      "80/tcp": [{"HostIp": "0.0.0.0", "HostPort": "8080"}, {"HostIp": "::", "HostPort": "8080"}],
      "9090/tcp": [{"HostIp": "127.0.0.1", "HostPort": "9090"}]
    },
    "HairpinMode": false,
    "LinkLocalIPv6Address": "",
    "LinkLocalIPv6PrefixLen": 0,
    "SecondaryIPAddresses": null,
    "SecondaryIPv6Addresses": null,
    "EndpointID": "",
    "Gateway": "",
    "GlobalIPv6Address": "",
    "GlobalIPv6PrefixLen": 0,
    "IPAddress": "",
    "IPPrefixLen": 0,
    "IPv6Gateway": "",
    "MacAddress": "",
    "Networks": {
      "backend": {
        "IPAMConfig": {"IPv4Address": "172.20.0.10"},
        "Links": null,
        "Aliases": ["app", "3f4e5d6c7b8a"],
        "MacAddress": "02:42:ac:14:00:0a",
        "DriverOpts": null,
        "GwPriority": 10,
        "NetworkID": "b7c9d1e3f5a7b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a3b5c7d9e1f3a5b7c9",
        "EndpointID": "c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a3b5c7d9e1f3a5b7c9d1e3f5a7b9c1d3",
        "Gateway": "172.20.0.1",
        "IPAddress": "172.20.0.10",
        "IPPrefixLen": 16,
        "IPv6Gateway": "",
        "GlobalIPv6Address": "",
        "GlobalIPv6PrefixLen": 0,
        "DNSNames": ["app-web", "app", "3f4e5d6c7b8a"]
      },
      "frontend": {
        "IPAMConfig": null,
        "Links": ["shop-db-1:db"],
        "Aliases": ["web", "3f4e5d6c7b8a"],
        "MacAddress": "02:42:ac:15:00:03",
        "GwPriority": 0,
        "DriverOpts": {"com.docker.network.endpoint.sysctls": "net.ipv4.conf.IFNAME.log_martians=1"},
        "NetworkID": "a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6c8d0e2f4a6b8",
        "EndpointID": "d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6c8d0e2f4a6b8c0d2e4",
        "Gateway": "172.21.0.1",
        "IPAddress": "172.21.0.3",
        "IPPrefixLen": 16,
        "IPv6Gateway": "",
        "GlobalIPv6Address": "",
        "GlobalIPv6PrefixLen": 0,
        "DNSNames": ["app-web", "web", "3f4e5d6c7b8a"]
      }
    }
  }
}"""

    /** `docker run --network container:<vpn-id> --name sidecar alpine:3.20 sleep infinity` (stopped). */
    const val INSPECT_CONTAINER_NETMODE = """{
  "Id": "5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f",
  "Created": "2026-10-02T10:00:00.000000000Z",
  "Path": "sleep",
  "Args": ["infinity"],
  "State": {
    "Status": "exited",
    "Running": false,
    "Paused": false,
    "Restarting": false,
    "OOMKilled": false,
    "Dead": false,
    "Pid": 0,
    "ExitCode": 137,
    "Error": "",
    "StartedAt": "2026-10-02T10:00:01.000000000Z",
    "FinishedAt": "2026-10-03T10:00:01.000000000Z"
  },
  "Image": "sha256:91ef0af61f39ece4d6ca1c7b3e0d2c6b5f4e3a2b1c0d9e8f7a6b5c4d3e2f1a0b",
  "Name": "/sidecar",
  "RestartCount": 0,
  "Driver": "overlay2",
  "Platform": "linux",
  "HostConfig": {
    "Binds": null,
    "ContainerIDFile": "",
    "LogConfig": {"Type": "json-file", "Config": {}},
    "NetworkMode": "container:0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b",
    "PortBindings": {},
    "RestartPolicy": {"Name": "no", "MaximumRetryCount": 0},
    "AutoRemove": false,
    "VolumeDriver": "",
    "VolumesFrom": null,
    "ConsoleSize": [0, 0],
    "CapAdd": null,
    "CapDrop": null,
    "CgroupnsMode": "private",
    "Dns": [],
    "DnsOptions": [],
    "DnsSearch": [],
    "ExtraHosts": null,
    "GroupAdd": null,
    "IpcMode": "private",
    "Cgroup": "",
    "Links": null,
    "OomScoreAdj": 0,
    "PidMode": "",
    "Privileged": false,
    "PublishAllPorts": false,
    "ReadonlyRootfs": false,
    "SecurityOpt": null,
    "UTSMode": "",
    "UsernsMode": "",
    "ShmSize": 67108864,
    "Runtime": "runc",
    "Isolation": "",
    "Memory": 0,
    "NanoCpus": 0,
    "Devices": [],
    "MaskedPaths": ["/proc/asound", "/proc/acpi", "/proc/kcore"],
    "ReadonlyPaths": ["/proc/bus", "/proc/fs"]
  },
  "Mounts": [],
  "Config": {
    "Hostname": "0a1b2c3d4e5f",
    "Domainname": "",
    "User": "",
    "AttachStdin": false,
    "AttachStdout": false,
    "AttachStderr": false,
    "ExposedPorts": {"8000/tcp": {}},
    "Tty": false,
    "OpenStdin": false,
    "StdinOnce": false,
    "Env": ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"],
    "Cmd": ["sleep", "infinity"],
    "Image": "alpine:3.20",
    "Volumes": null,
    "WorkingDir": "",
    "Entrypoint": null,
    "OnBuild": null,
    "Labels": {},
    "MacAddress": "02:42:ac:11:00:09"
  },
  "NetworkSettings": {
    "Bridge": "",
    "SandboxID": "",
    "SandboxKey": "",
    "Ports": {},
    "HairpinMode": false,
    "EndpointID": "",
    "Gateway": "",
    "IPAddress": "",
    "IPPrefixLen": 0,
    "MacAddress": "",
    "Networks": {}
  }
}"""

    /** `docker run -d --rm --name scratchpad -v /data busybox:1.36 httpd -f` on the default bridge. */
    const val INSPECT_AUTOREMOVE = """{
  "Id": "7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b",
  "Created": "2026-10-16T12:00:00.000000000Z",
  "Path": "httpd",
  "Args": ["-f"],
  "State": {
    "Status": "running",
    "Running": true,
    "Paused": false,
    "Restarting": false,
    "OOMKilled": false,
    "Dead": false,
    "Pid": 5511,
    "ExitCode": 0,
    "Error": "",
    "StartedAt": "2026-10-16T12:00:00.500000000Z",
    "FinishedAt": "0001-01-01T00:00:00Z"
  },
  "Image": "sha256:65ad0d468eb1c558bf7f4e64e790f586e9eda649ee9f130cd0e835b292bbc5ac",
  "Name": "/scratchpad",
  "RestartCount": 0,
  "Driver": "overlay2",
  "Platform": "linux",
  "HostConfig": {
    "Binds": null,
    "ContainerIDFile": "",
    "LogConfig": {"Type": "json-file", "Config": {}},
    "NetworkMode": "bridge",
    "PortBindings": {},
    "RestartPolicy": {"Name": "no", "MaximumRetryCount": 0},
    "AutoRemove": true,
    "VolumeDriver": "",
    "VolumesFrom": null,
    "ConsoleSize": [0, 0],
    "CapAdd": null,
    "CapDrop": null,
    "CgroupnsMode": "private",
    "Dns": [],
    "DnsOptions": [],
    "DnsSearch": [],
    "ExtraHosts": null,
    "IpcMode": "private",
    "Links": null,
    "Privileged": false,
    "ShmSize": 67108864,
    "Runtime": "runc",
    "Devices": []
  },
  "Mounts": [
    {
      "Type": "volume",
      "Name": "f1e2d3c4b5a6978877665544332211ffeeddccbbaa99887766554433221100ff",
      "Source": "/var/lib/docker/volumes/f1e2d3c4b5a6978877665544332211ffeeddccbbaa99887766554433221100ff/_data",
      "Destination": "/data",
      "Driver": "local",
      "Mode": "",
      "RW": false,
      "Propagation": ""
    }
  ],
  "Config": {
    "Hostname": "7a8b9c0d1e2f",
    "Domainname": "",
    "User": "",
    "AttachStdin": false,
    "AttachStdout": false,
    "AttachStderr": false,
    "Tty": false,
    "OpenStdin": false,
    "StdinOnce": false,
    "Env": ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"],
    "Cmd": ["httpd", "-f"],
    "Image": "busybox:1.36",
    "Volumes": {"/data": {}},
    "WorkingDir": "",
    "Entrypoint": null,
    "OnBuild": null,
    "Labels": {}
  },
  "NetworkSettings": {
    "Bridge": "",
    "SandboxID": "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899",
    "SandboxKey": "/var/run/docker/netns/aa11bb22cc33",
    "Ports": {},
    "HairpinMode": false,
    "EndpointID": "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0",
    "Gateway": "172.17.0.1",
    "IPAddress": "172.17.0.4",
    "IPPrefixLen": 16,
    "MacAddress": "02:42:ac:11:00:04",
    "Networks": {
      "bridge": {
        "IPAMConfig": null,
        "Links": null,
        "Aliases": null,
        "MacAddress": "02:42:ac:11:00:04",
        "DriverOpts": null,
        "NetworkID": "4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c",
        "EndpointID": "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0",
        "Gateway": "172.17.0.1",
        "IPAddress": "172.17.0.4",
        "IPPrefixLen": 16,
        "IPv6Gateway": "",
        "GlobalIPv6Address": "",
        "GlobalIPv6PrefixLen": 0,
        "DNSNames": null
      }
    }
  }
}"""

    /**
     * Docker Compose v2 service `node` (project `shop`) with
     * `volumes: [./src:/app/src, db:/var/lib/data, /app/node_modules]`: compose sends the named and the
     * anonymous volume as `HostConfig.Mounts`, the anonymous one without a `Source`.
     */
    const val INSPECT_COMPOSE_ANON = """{
  "Id": "c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4",
  "Created": "2026-10-10T07:30:00.000000000Z",
  "Path": "docker-entrypoint.sh",
  "Args": ["npm", "start"],
  "State": {
    "Status": "running",
    "Running": true,
    "Paused": false,
    "Restarting": false,
    "OOMKilled": false,
    "Dead": false,
    "Pid": 7702,
    "ExitCode": 0,
    "Error": "",
    "StartedAt": "2026-10-10T07:30:01.000000000Z",
    "FinishedAt": "0001-01-01T00:00:00Z"
  },
  "Image": "sha256:e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5",
  "Name": "/shop-node-1",
  "RestartCount": 0,
  "Driver": "overlay2",
  "Platform": "linux",
  "HostConfig": {
    "Binds": ["/srv/shop/src:/app/src:rw"],
    "ContainerIDFile": "",
    "LogConfig": {"Type": "json-file", "Config": {}},
    "NetworkMode": "shop_default",
    "PortBindings": {},
    "RestartPolicy": {"Name": "always", "MaximumRetryCount": 0},
    "AutoRemove": false,
    "VolumeDriver": "",
    "VolumesFrom": null,
    "ConsoleSize": [0, 0],
    "CapAdd": null,
    "CapDrop": null,
    "CgroupnsMode": "private",
    "Dns": null,
    "DnsOptions": null,
    "DnsSearch": null,
    "ExtraHosts": [],
    "IpcMode": "private",
    "Links": null,
    "Privileged": false,
    "ShmSize": 67108864,
    "Runtime": "runc",
    "Devices": null,
    "Mounts": [
      {"Type": "volume", "Source": "shop_db", "Target": "/var/lib/data", "VolumeOptions": {}},
      {"Type": "volume", "Target": "/app/node_modules", "VolumeOptions": {}}
    ]
  },
  "Mounts": [
    {
      "Type": "bind",
      "Source": "/srv/shop/src",
      "Destination": "/app/src",
      "Mode": "rw",
      "RW": true,
      "Propagation": "rprivate"
    },
    {
      "Type": "volume",
      "Name": "shop_db",
      "Source": "/var/lib/docker/volumes/shop_db/_data",
      "Destination": "/var/lib/data",
      "Driver": "local",
      "Mode": "z",
      "RW": true,
      "Propagation": ""
    },
    {
      "Type": "volume",
      "Name": "5c0d8e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6c8d",
      "Source": "/var/lib/docker/volumes/5c0d8e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6c8d/_data",
      "Destination": "/app/node_modules",
      "Driver": "local",
      "Mode": "z",
      "RW": true,
      "Propagation": ""
    }
  ],
  "Config": {
    "Hostname": "c3d4e5f6a7b8",
    "Domainname": "",
    "User": "node",
    "AttachStdin": false,
    "AttachStdout": true,
    "AttachStderr": true,
    "Tty": false,
    "OpenStdin": false,
    "StdinOnce": false,
    "Env": ["NODE_ENV=production", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"],
    "Cmd": ["npm", "start"],
    "Image": "node:22-alpine",
    "Volumes": null,
    "WorkingDir": "/app",
    "Entrypoint": ["docker-entrypoint.sh"],
    "OnBuild": null,
    "Labels": {
      "com.docker.compose.project": "shop",
      "com.docker.compose.service": "node",
      "com.docker.compose.version": "2.29.7"
    }
  },
  "NetworkSettings": {
    "Bridge": "",
    "SandboxID": "d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5",
    "SandboxKey": "/var/run/docker/netns/d4e5f6a7b8c9",
    "Ports": {},
    "HairpinMode": false,
    "EndpointID": "",
    "Gateway": "",
    "IPAddress": "",
    "IPPrefixLen": 0,
    "MacAddress": "",
    "Networks": {
      "shop_default": {
        "IPAMConfig": null,
        "Links": null,
        "Aliases": ["shop-node-1", "node"],
        "MacAddress": "02:42:ac:16:00:02",
        "DriverOpts": null,
        "GwPriority": 0,
        "NetworkID": "f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6",
        "EndpointID": "a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8",
        "Gateway": "172.22.0.1",
        "IPAddress": "172.22.0.2",
        "IPPrefixLen": 16,
        "IPv6Gateway": "",
        "GlobalIPv6Address": "",
        "GlobalIPv6PrefixLen": 0,
        "DNSNames": ["shop-node-1", "node", "c3d4e5f6a7b8"]
      }
    }
  }
}"""

    /** `GET /images/{id}/json` for the image behind [INSPECT_FULL] (`ghcr.io/example/app:1.0`). */
    const val IMAGE_CONFIG = """{
  "Id": "sha256:7d1c3e5f9a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d",
  "RepoTags": ["ghcr.io/example/app:1.0"],
  "RepoDigests": ["ghcr.io/example/app@sha256:0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f"],
  "Parent": "",
  "Comment": "buildkit.dockerfile.v0",
  "Created": "2026-09-20T14:02:11.987654321Z",
  "DockerVersion": "",
  "Author": "",
  "Config": {
    "Hostname": "",
    "Domainname": "",
    "User": "",
    "AttachStdin": false,
    "AttachStdout": false,
    "AttachStderr": false,
    "ExposedPorts": {"80/tcp": {}},
    "Tty": false,
    "OpenStdin": false,
    "StdinOnce": false,
    "Env": [
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
      "APP_VERSION=1"
    ],
    "Cmd": ["app", "serve"],
    "Image": "",
    "Volumes": {"/data": {}},
    "WorkingDir": "/app",
    "Entrypoint": ["/entrypoint.sh"],
    "OnBuild": null,
    "Labels": {
      "org.opencontainers.image.title": "app",
      "org.opencontainers.image.version": "1.0"
    },
    "StopSignal": "SIGQUIT"
  },
  "Architecture": "amd64",
  "Os": "linux",
  "Size": 48213077,
  "GraphDriver": {
    "Data": {
      "LowerDir": "/var/lib/docker/overlay2/9c8b7a6f5e4d/diff",
      "MergedDir": "/var/lib/docker/overlay2/1a2b3c4d5e6f/merged",
      "UpperDir": "/var/lib/docker/overlay2/1a2b3c4d5e6f/diff",
      "WorkDir": "/var/lib/docker/overlay2/1a2b3c4d5e6f/work"
    },
    "Name": "overlay2"
  },
  "RootFS": {
    "Type": "layers",
    "Layers": [
      "sha256:63ca1fbb43ae5034640e5e6cb3e083e05c290072c5366fcaa9d62435a4cced85",
      "sha256:2b8b8e6f1c3d5e7f9a1b3c5d7e9f1a3b5c7d9e1f3a5b7c9d1e3f5a7b9c1d3e5f"
    ]
  },
  "Metadata": {"LastTagTime": "0001-01-01T00:00:00Z"}
}"""
}
