# Apache Maven Jenkins Shared Libraries

This repository contains the Jenkins shared libraries that define the standard build process for Apache Maven subprojects.

## asfMavenTlpPlgnBuild() (building Maven plugins)

### Accepted parameters:

- `os`: array of possible os to build projects (default: `['linux']`)
- `jdks`: array of jdks used for the build (default: `['8','11','17']`)
- `maven`: array of maven versions used for build (default: `['3.6.x', '3.9.x']`)
- `siteJdk`: array of jdks used for the site build (default: `['11']`)
- `siteMvn`: jdk used to build the site (default: `3.9.x`)
- `tmpWs`: boolean to shorten working directory on windows platform
- `branchesToNotify`: array of branches to send notifications of the build (default: `['master', 'main']`)
- `maven4xBuild`: indicate that build by Maven 4.x only

Example to use a specific set of jdks and maven core

```
asfMavenTlpPlgnBuild(jdks:[ "8", "11" ], maven: ["3.8.x"])
```

## asfMavenTlpStdBuild() (building Other projects)

### Accepted parameters:

- `os`: array of possible os to build projects (default: `['linux']`)
- `jdks`: array of jdks used for the build (default: `['8','11','17']`)
- `maven`: maven versions used for build (default: `3.9.x`)
- `tmpWs`: boolean to shorten working directory on windows platform
- `branchesToNotify`: array of branches to send notifications of the build (default: `['master', 'main']`)

Example to use a specific set of jdks and maven core

```
asfMavenTlpStdBuild(jdks:[ "8", "11" ], maven: "3.6.x")
```

## asfMavenTlpPlgnBuild4x() (building Maven plugins with Maven 4.x)

call `asfMavenTlpPlgnBuild` with parameters:

- `maven4xBuild`:  with default: `true`
- `jdks`: with default: `['17', '21']`
- `maven`: with default `['4.0.x']`
- `siteJdk`: with default: `['21']`
- `siteMvn`: with default: `4.0.x`
