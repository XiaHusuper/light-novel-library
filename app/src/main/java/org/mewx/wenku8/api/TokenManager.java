package org.mewx.wenku8.api;

/** JNI name must match the unmodified upstream runtime. */
public final class TokenManager {
    static { System.loadLibrary("cpp"); }
    private TokenManager() {}
    public static native String getMagicVersionCpp(String version);
}
