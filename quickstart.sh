#!/bin/sh

create_pkgsdir() {
	git clone https://github.com/void-linux/void-packages.git PACKAGESDIR
}

[ -d BUILDDIR ] || mkdir BUILDDIR

[ -d HOSTDIR ] || mkdir HOSTDIR

[ -d PACKAGESDIR/.git ] || create_pkgsdir

export DXPB_LOCAL_BUILD_DIR="$(pwd)/BUILDDIR"
export DXPB_BINPKG_DIR="$(pwd)/HOSTDIR"
export OWNED_PACKAGES_PATH="$(pwd)/PACKAGESDIR"
export DXPB_ACCEPTED_HOST_ARCHS='["x86_64" "x86_64-musl"]'
