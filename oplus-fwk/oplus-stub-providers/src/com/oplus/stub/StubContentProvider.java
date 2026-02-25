/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Stub ContentProvider for missing OPlus providers.
 * Prevents "Failed to find provider info" log spam from
 * OnePlusCamera and OnePlusGallery on custom ROMs.
 */
package com.oplus.stub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class StubContentProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return new MatrixCursor(projection != null ? projection : new String[]{"_id"});
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }
}
