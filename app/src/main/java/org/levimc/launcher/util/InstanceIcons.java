package org.levimc.launcher.util;

import android.widget.ImageView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.versions.GameVersion;

public final class InstanceIcons {
    private InstanceIcons() {
    }

    public static void bind(ImageView icon, GameVersion version) {
        if (icon == null) return;
        bindUri(icon, version == null ? null
                : InstanceShortcutManager.getSavedIconUri(icon.getContext(), version));
    }

    public static void bindUri(ImageView icon, String uri) {
        if (icon == null) return;
        Glide.with(icon).clear(icon);
        icon.setImageTintList(null);
        icon.clearColorFilter();
        icon.setImageResource(R.drawable.ic_minecraft_cube);
        if (uri == null || uri.isEmpty()) return;
        Glide.with(icon)
                .load(uri)
                .centerCrop()
                .placeholder(R.drawable.ic_minecraft_cube)
                .error(R.drawable.ic_minecraft_cube)
                .dontAnimate()
                .into(icon);
    }
}