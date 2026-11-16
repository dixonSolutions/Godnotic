package io.github.dixonsolutions.godnotic;

/** Names shared by the release workflow and the APK updater. */
final class ReleaseAsset {
    private ReleaseAsset() {}

    static boolean compatible(String name, String version, String[] abis, int sdk) {
        if (abis == null || abis.length == 0 || abis[0] == null) return false;
        return matches(name, version, abis[0], sdk, true)
                || matches(name, version, abis[0], sdk, false);
    }

    static boolean matches(String name, String version, String abi, int sdk, boolean live) {
        if (!live) return name.equals("Godnotic-" + version + "-" + abi + ".apk")
                && (abi.equals("arm64-v8a") || abi.equals("armeabi-v7a") || abi.equals("x86_64"));
        if (sdk < 30) return false;
        String suffix;
        switch (abi) {
            case "arm64-v8a": suffix = "arm64"; break;
            case "armeabi-v7a": suffix = "arm32"; break;
            case "x86_64": suffix = "x64"; break;
            default: return false;
        }
        return name.equals("Godnotic-live-" + version + "-" + suffix + ".apk");
    }
}
