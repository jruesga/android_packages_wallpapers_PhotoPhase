/*
 * Copyright (C) 2015 Jorge Ruesga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ruesga.android.wallpapers.photophase;

import android.content.Context;
import android.support.v4.content.ContextCompat;

import com.ruesga.android.wallpapers.photophase.preferences.PreferencesProvider.Preferences;
import com.ruesga.android.wallpapers.photophase.utils.GLESUtil.GLColor;

/**
 * A class that defines some wallpaper GLColor colors.
 */
public class Colors {

    private static GLColor sBackground = new GLColor(0);
    private static GLColor sBorder = new GLColor(0);
    private static GLColor sOverlay = new GLColor(0);

    /**
     * This method should be called on initialization for load the preferences color
     */
    public static void register(Context ctx) {
        sBackground = Preferences.General.getBackgroundColor(ctx);
        sBorder = Preferences.General.Borders.getBorderColor(ctx);
        sOverlay = new GLColor(ContextCompat.getColor(ctx, R.color.wallpaper_overlay_color));
    }

    public static GLColor getBackground() {
        return sBackground;
    }

    public static void setBackground(GLColor background) {
        Colors.sBackground = background;
    }

    public static GLColor getBorder() {
        return sBorder;
    }

    public static void setBorder(GLColor border) {
        Colors.sBorder = border;
    }

    public static GLColor getOverlay() {
        return sOverlay;
    }
}
