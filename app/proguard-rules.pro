-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

-keep class com.discord.** { *; }
-keep class org.levimc.launcher.core.discord.DiscordPresenceNative { *; }

-keep,includedescriptorclasses class org.levimc.** { *; }

-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

-keep,includedescriptorclasses class org.levimc.launcher.core.minecraft.MinecraftActivity { *; }
-keep,includedescriptorclasses class org.levimc.launcher.util.NativeBridgeHelper { *; }
-keep,includedescriptorclasses class org.levimc.launcher.preloader.** { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.mods.Mod { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.mods.ModManager { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.mods.inbuilt.ExternalModBridge { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.mods.inbuilt.ExternalModBridge$* { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.mods.inbuilt.nativemod.** { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.content.nbt.NbtTag { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.content.nbt.NbtDocument { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.content.nbt.NativeNbt { *; }
-keep,includedescriptorclasses class org.levimc.launcher.core.content.leveldb.WorldDatabase { *; }
-keep class org.levimc.launcher.R$drawable { *; }
-keep class org.levimc.launcher.** implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keep class org.levimc.launcher.settings.FeatureSettings {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.auth.MsftAccountStore$MsftAccount {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.versions.VersionProfileMetadata {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.util.InstanceBackupManager$BackupManifest {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.curseforge.models.** {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.modcatalog.ModCatalog {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.modcatalog.ModCatalog$* {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.news.NewsFeed {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.news.NewsItem {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.news.ChangelogManager$Changelog {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.news.ChangelogManager$Section {
    !static !transient <fields>;
    <init>();
}

-keep class org.levimc.launcher.core.news.ChangelogManager$Item {
    !static !transient <fields>;
    <init>();
}

-dontwarn java.awt.**
-dontwarn javafx.**
-dontwarn javax.swing.**
-dontwarn com.sun.net.httpserver.**
-dontwarn java.net.http.**
-dontwarn reactor.**
-dontwarn org.reactivestreams.**
-dontwarn io.netty.**
-dontwarn net.lenni0451.commons.httpclient.executor.HttpClientExecutor
-dontwarn lombok.Generated
