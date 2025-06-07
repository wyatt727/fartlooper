# Vendor Submodule Versions

This file records the git SHAs and versions of our vendor submodules.

## Current Submodules (as of 2025-06-07)

| Submodule | Repository | SHA | Version/Tag |
|-----------|------------|-----|-------------|
| **nanohttpd** | https://github.com/NanoHttpd/nanohttpd | `efb2ebf85a2b06f7c508aba9eaad5377e3a01e81` | nanohttpd-project-2.3.1-76-gefb2ebf |
| **cling** | https://github.com/4thline/cling | `0ca10d5e73bdc8062eeffad57d67dd8ee260379c` | 2.0-alpha3-104-g0ca10d5 |
| **mdns** | https://github.com/jmdns/jmdns | `9f89da7934e9fb2de123022e8df74d191bb43c94` | jmdns-3.4.2-168-g9f89da7 |

## Build Integration

These submodules are integrated into our Gradle build via:
- NanoHTTPD: `implementation project(':vendor:nanohttpd:core')`
- Cling: `implementation project(':vendor:cling:core')` and `implementation project(':vendor:cling:support')`
- mDNS: `implementation project(':vendor:mdns')`

## Updating Submodules

To update to latest versions:
```bash
git submodule update --remote
git add vendor/
git commit -m "Update vendor submodules"
``` 