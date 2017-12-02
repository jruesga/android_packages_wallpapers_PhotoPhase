/*
 * Copyright (C) 2017 Jorge Ruesga
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

package com.ruesga.android.wallpapers.photophase.cast;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

public final class CastTaskManagerFactory {
    private static final String TAG = "CastTaskManagerFactory";

    public static ICastTaskManager newCastTaskManager(String clazz) {
        try {
            Log.d(TAG, "Using CastTaskManager implementation: " + clazz);
            return (ICastTaskManager) Class.forName(clazz).newInstance();
        } catch (Exception ex) {
            // Ignore
        }
        throw new IllegalAccessError("Failed to obtain CastTaskManager implementation: " + clazz);
    }
}
