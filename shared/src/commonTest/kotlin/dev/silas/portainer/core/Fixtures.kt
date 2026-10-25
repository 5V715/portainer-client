package dev.silas.portainer.core

/** Test fixtures as constants: commonTest resources are not readable on every KMP target (wasm, iOS). */
object Fixtures {
    /** Shape of Docker Engine `GET /containers/json?all=1`. */
    const val CONTAINERS = """[
  {
    "Id": "8dfafdbc3a40a1b7c2f1d9e0a5b6c7d8e9f0a1b2c3d4e5f60718293a4b5c6d7e",
    "Names": ["/web"],
    "Image": "nginx:1.27",
    "ImageID": "sha256:a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
    "Command": "/docker-entrypoint.sh nginx -g 'daemon off;'",
    "Created": 1760000000,
    "Ports": [{"IP": "0.0.0.0", "PrivatePort": 80, "PublicPort": 8080, "Type": "tcp"}],
    "Labels": {"maintainer": "NGINX Docker Maintainers"},
    "State": "running",
    "Status": "Up 3 hours (healthy)",
    "HostConfig": {"NetworkMode": "bridge"},
    "NetworkSettings": {"Networks": {"bridge": {"IPAddress": "172.17.0.2", "Gateway": "172.17.0.1"}}},
    "Mounts": []
  },
  {
    "Id": "1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f",
    "Names": ["/api"],
    "Image": "registry.example.com/team/api:2.3.1",
    "ImageID": "sha256:b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1",
    "Command": "node server.js",
    "Created": 1760000100,
    "Ports": [{"IP": "0.0.0.0", "PrivatePort": 3000, "PublicPort": 3000, "Type": "tcp"}],
    "Labels": null,
    "State": "running",
    "Status": "Up 2 hours (unhealthy)",
    "HostConfig": {"NetworkMode": "bridge"},
    "NetworkSettings": {"Networks": {"bridge": {"IPAddress": "172.17.0.3", "Gateway": "172.17.0.1"}, "backend": {"IPAddress": "172.21.0.2", "Gateway": "172.21.0.1"}}},
    "Mounts": [{"Type": "volume", "Name": "api-data", "Source": "/var/lib/docker/volumes/api-data/_data", "Destination": "/data", "Driver": "local", "Mode": "z", "RW": true, "Propagation": ""}]
  },
  {
    "Id": "2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f70",
    "Names": ["/old-batch"],
    "Image": "alpine:3.20",
    "ImageID": "sha256:c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2",
    "Command": "sh -c 'echo done'",
    "Created": 1759000000,
    "Ports": [],
    "Labels": {},
    "State": "exited",
    "Status": "Exited (0) 2 days ago",
    "HostConfig": {"NetworkMode": "bridge"},
    "NetworkSettings": {"Networks": {"bridge": {"IPAddress": "", "Gateway": ""}}},
    "Mounts": [{"Type": "volume", "Name": "cache", "Source": "/var/lib/docker/volumes/cache/_data", "Destination": "/cache", "Driver": "local", "Mode": "z", "RW": true, "Propagation": ""}]
  },
  {
    "Id": "3e4f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f7081",
    "Names": ["/media-jellyfin-1"],
    "Image": "jellyfin/jellyfin:10.9.11",
    "ImageID": "sha256:d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3",
    "Command": "/jellyfin/jellyfin",
    "Created": 1760000200,
    "Ports": [{"IP": "0.0.0.0", "PrivatePort": 8096, "PublicPort": 8096, "Type": "tcp"}],
    "Labels": {
      "com.docker.compose.project": "media",
      "com.docker.compose.service": "jellyfin",
      "com.docker.compose.version": "2.29.7"
    },
    "State": "running",
    "Status": "Up 5 minutes (health: starting)",
    "HostConfig": {"NetworkMode": "media_default"},
    "NetworkSettings": {"Networks": {"media_default": {"IPAddress": "172.20.0.2", "Gateway": "172.20.0.1"}}},
    "Mounts": [{"Type": "bind", "Source": "/srv/media", "Destination": "/media", "Mode": "ro", "RW": false, "Propagation": "rprivate"}]
  }
]"""
}
