/*
 *  Copyright (c) 2024 Ashish Yadav <mailtoashish693@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ichi2.anki.noteeditor.NoteEditorLauncher

/**
 * Builder class for creating intents related to image occlusion in the [NoteEditorFragment].
 */
class ImageOcclusionIntentBuilder(
    private val context: Context,
) {
    fun buildIntent(imageUri: Uri?): Intent = NoteEditorLauncher.ImageOcclusion(imageUri).toIntent(context)
}
