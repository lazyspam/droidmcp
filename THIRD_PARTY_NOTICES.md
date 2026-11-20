# Third-party components

The APK bundles these prebuilt binaries from the [Termux](https://termux.dev) package repository
(`android/app/src/main/jniLibs/`), renamed to `lib*.so` so Android allows executing them:

| File | Project | License | Source |
|---|---|---|---|
| `libproot.so`, `libproot-loader*.so` | PRoot (Termux fork) | GPL-2.0 | https://github.com/termux/proot |
| `libtalloc.so` | talloc | LGPL-3.0 | https://talloc.samba.org |
| `libandroid-shmem.so` | libandroid-shmem | BSD-3-Clause | https://github.com/termux/libandroid-shmem |

They are separate programs that DroidMCP runs; DroidMCP's own code is MIT-licensed.
Alpine Linux itself is downloaded at runtime from dl-cdn.alpinelinux.org, not bundled.
