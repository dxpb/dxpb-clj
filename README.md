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

FIXME: listing of options this app accepts.

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

Copyright © 2020 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
