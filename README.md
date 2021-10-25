# dxpb-clj

FIXME: description

## Quickstarts

### Play with importing the package graph

- Clone the repo
- Set and probably export the following environmental variables:
- `ARCH_SPEC_WHATGOESHEREDOESNOTMATTER='{:XBPS_ARCH "x86_64-musl"
  :XBPS_TARGET_ARCH "x86_64-musl" :cross false}'`
- `OWNED_PACKAGES_PATH=~/void-packages` (or wherever your void-packages is)
- run `lein repl`
- Wait for the dependencies to be installed
- Run the following code blocks one per line in the repl
- `(start-arch-specs)`
- Just to prove it works at all:
- `(time (import-packages :package-list '("gcc")))`
- And then to do the big one:
- `(time (import-packages))`

## Installation

Download from http://example.com/FIXME.

## Usage

FIXME: explanation

    $ java -jar dxpb-clj-0.1.0-standalone.jar [args]

## Options

Most of the configuration is input via environment variables or a configuration
file. Both of these options are viable depending on your deployment use case.

Environment variables are forced to lowercase, and underscores are replaced
with hyphens. `NUM_XBPS_SRC_READERS` becomes `num-xbps-src-readers`, and,
because it becomes a clojure keyword, it becomes `:num-xbps-src-readers`.

Among the options available:

#### DXPB Core

- `DXPB_SERVER_DIR`: string, path to where dxpb should save the database.
  Defaults to `./datadir`.

#### Package presence

- `DXPB_BINPKG_DIR`: string, path to a place to list packages. A hostdir.
- `DXPB_REPODATA_DIR`: string, a place where you can find repodata files. A
  hostdir. Defaults to `DXPB_BINPKG_DIR` if unset.

#### Shell Building Plugin

- `DXPB_LOCAL_BUILD_DIR`: string, path to a directory where dxpb will run
  builds. The build script needs to own this directory. The script will rely
  on `DXPB_BINPKG_DIR` to tell `xbps-src` where to register packages. Otherwise
  `DXPB_LOCAL_BUILD_DIR/hostdir` will be the hostdir.
- `DXPB_ACCEPTED_HOST_ARCHS`: edn list of strings, strings are accepted
  XBPS_ARCH values matching the current system. Defaults to
  `'["x86_64", "x86_64-musl"]'`

#### Nomad Plugin

- `DXPB_NOMAD_BUILD_JOB`: string, nomad job name
- `DXPB_NOMAD_ENDPOINT`: string, http connection
- `DXPB_NOMAD_HTTP_ENDPOINT`: edn map, can be passed as string, proxy settings go here.

#### Package Import

- `NUM_XBPS_SRC_READERS`: integer, number of parallel xbps-src readers
- `OWNED_PACKAGES_PATH`: string, file path. The name refers to an "owned
  checkout of void-packages," that is to say a git clone that dxpb can own and
  operate itself. No other processes or building should happen in this
  checkout. dxpb owns it.

#### Arch Specifications

All of these match the pattern `ARCH_SPEC_.+` and you need to choose names for
the arch specifications. These are EDN maps (can be passed as strings) such as
`{:XBPS_ARCH "x86_64" :XBPS_TARGET_ARCH "x86_64"  :cross false}` where all keys
are required and additional keys are ignored.

Coming up with valid arch specifications is an adventure in understanding
xbps-src enough to do a good job.

## Examples

...

### Bugs

...

### Assumptions Built In

If you want to use this on a package manager other than xbps, with xbps-src,
please consider the following assumptions that are baked deep into the
codebase. They hold true for xbps, they may not hold true for your application.

1. Every package has exactly one current version (a previous version may be in
   the repos).
2. That version is the same across all builds
3. Every package has a unique package name.
4. Packages have dependencies that must exist before they can build.
5. Once a package is built for a target architecture, it's available on that
   target architecture.
6. You have a way to ensure a package is available to your builders once it's
   available to the DXPB server.

### That You Think
### Might be Useful

## License

Copyright © 2020-2021 Toyam Cox

This program and the accompanying materials are made available under the
terms of the OpenBSD-style ISC license.
