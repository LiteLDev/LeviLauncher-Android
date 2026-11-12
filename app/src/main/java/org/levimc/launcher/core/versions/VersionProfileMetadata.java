package org.levimc.launcher.core.versions;

public class VersionProfileMetadata {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public String profileId;
    public String directoryName;
    public String versionName;
    public String displayName;
    public boolean versionIsolation;
    public boolean launchVertically;
    public boolean modMenuEnabled = true;
    public boolean installed;
    public String packageName;
    public long updatedAt;
}
