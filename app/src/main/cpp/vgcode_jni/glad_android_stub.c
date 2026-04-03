/**
 * Stub for glad loader on Android.
 * On Android, GLES3 functions are provided by linking GLESv3 directly —
 * no dynamic loading needed. libvgcode calls gladLoaderLoadGLES2() during
 * init and gladLoaderUnloadGLES2() during shutdown; we make them no-ops.
 */

int gladLoaderLoadGLES2(void) {
    return 1;  /* success */
}

void gladLoaderUnloadGLES2(void) {
    /* no-op */
}
