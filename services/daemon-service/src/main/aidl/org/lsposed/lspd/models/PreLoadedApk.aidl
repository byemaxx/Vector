package org.lsposed.lspd.models;

parcelable PreLoadedApk {
    List<SharedMemory> preLoadedDexes;
    List<String> moduleClassNames;
    List<String> moduleLibraryNames;
    boolean legacy;
    // Staged native-library directory for system_server. Null for ordinary app processes, modules
    // without native libraries for the current ABI, or failed staging.
    @nullable String nativeLibraryDir;
}
