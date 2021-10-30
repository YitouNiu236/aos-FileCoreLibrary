package com.archos.filecorelibrary;

import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentSender;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

/**
 * 9th July 2021.
 * <p>
 * A class to read write external shared storage for android R.
 * Since Android 11 you can only access the android specified directories such as
 * DCIM, Download, Documents, Pictures, Movies, Music etc.
 * <p>
 * This class is just for an example class.
 *
 * @author <a href="https://github.com/fiftyonemoon">hardkgosai</a>.
 * @since 1.0.3.2
 */
public class FileUtilsQ {

    private static final Logger log = LoggerFactory.getLogger(FileUtilsQ.class);

    private static Context mContext;

    private static volatile FileUtilsQ sInstance;

    private static ActivityResultLauncher<IntentSenderRequest> mDeleteLauncher;

    public static String publicAppDirectory = null;
    public static String privateAppDirectory = null;

    public static void setDeleteLauncher(ActivityResultLauncher<IntentSenderRequest> launcher) {
        mDeleteLauncher = launcher;
    }

    public static ActivityResultLauncher<IntentSenderRequest> getDeleteLauncher() {
        return mDeleteLauncher;
    }

    // get the instance, context is used for initial context injection
    public static FileUtilsQ getInstance(Context context) {
        if (context == null) log.warn("getInstance: context passed is null!!!");
        else if (mContext == null) mContext = context;
        if (sInstance == null) {
            synchronized(FileUtilsQ.class) {
                if (sInstance == null) sInstance = new FileUtilsQ(context.getApplicationContext());
            }
        }
        return sInstance;
    }

    /** may return null but no Context required */
    public static FileUtilsQ peekInstance() {
        return sInstance;
    }

    public FileUtilsQ(Context context) {
        mContext = context;
        publicAppDirectory = mContext.getExternalFilesDir(null).getPath();
        privateAppDirectory = mContext.getFilesDir().getPath();
    }

    public FileUtilsQ with(Context context) {
        mContext = context;
        return this;
    }

    /**
     * Create new media uri.
     */
    public static Uri create(String directory, String filename, String mimetype) {

        ContentResolver contentResolver = mContext.getContentResolver();

        ContentValues contentValues = new ContentValues();

        //Set filename, if you don't system automatically use current timestamp as name
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);

        //Set mimetype if you want
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimetype);

        //To create folder in Android directories use below code
        //pass your folder path here, it will create new folder inside directory
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, directory);
        }

        //pass new ContentValues() for no values.
        //Specified uri will save object automatically in android specified directories.
        //ex. MediaStore.Images.Media.EXTERNAL_CONTENT_URI will save object into android Pictures directory.
        //ex. MediaStore.Videos.Media.EXTERNAL_CONTENT_URI will save object into android Movies directory.
        //if content values not provided, system will automatically add values after object was written.
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues);
    }

    /**
     * Delete file.
     * <p>
     * If {@link ContentResolver} failed to delete the file, use trick,
     * SDK version is >= 29(Q)? use {@link SecurityException} and again request for delete.
     * SDK version is >= 30(R)? use {@link MediaStore#createDeleteRequest(ContentResolver, Collection)}.
     */
    public static Boolean delete(ActivityResultLauncher<IntentSenderRequest> launcher, Uri uri) {

        if (uri == null) return true;
        boolean isSuccessful = false;
        ContentResolver contentResolver = mContext.getContentResolver();

        try {
            // delete object using resolver
            log.debug("delete: uri " + uri);
            contentResolver.delete(uri, null, null);
            isSuccessful = true;
        } catch (SecurityException e) {
            PendingIntent pendingIntent = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                log.debug("delete: SecurityException Android >= R");
                ArrayList<Uri> collection = new ArrayList<>();
                collection.add(uri);
                pendingIntent = MediaStore.createDeleteRequest(contentResolver, collection);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                log.debug("delete: SecurityException Q<=Android<R");
                // if exception is recoverable then again send delete request using intent
                if (e instanceof RecoverableSecurityException) {
                    log.debug("delete: RecoverableSecurityException");
                    RecoverableSecurityException exception = (RecoverableSecurityException) e;
                    pendingIntent = exception.getUserAction().getActionIntent();
                }
            }
            if (pendingIntent != null) {
                log.debug("delete: pending intent not null");
                IntentSender sender = pendingIntent.getIntentSender();
                IntentSenderRequest request = new IntentSenderRequest.Builder(sender).build();
                launcher.launch(request);
                // TODO MARC correct
                isSuccessful = true;
            }
        }

        return isSuccessful;

    }

    /**
     * Rename file.
     *
     * @param uri    - filepath
     * @param rename - the name you want to replace with original.
     */
    public void rename(Uri uri, String rename) {

        //create content values with new name and update
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, rename);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mContext.getContentResolver().update(uri, contentValues, null);
        }
    }

    /**
     * Duplicate file.
     *
     * @param uri - filepath.
     */
    public Uri duplicate(Uri uri) {

        ContentResolver contentResolver = mContext.getContentResolver();

        Uri output = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new ContentValues());

        String input = getPathFromUri(uri);

        try (InputStream inputStream = new FileInputStream(input)) { //input stream

            OutputStream out = contentResolver.openOutputStream(output); //output stream

            byte[] buf = new byte[1024];
            int len;
            while ((len = inputStream.read(buf)) > 0) {
                out.write(buf, 0, len); //write input file data to output file
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        return output;
    }

    /**
     * Get file path from uri.
     */
    private String getPathFromUri(Uri uri) {
        Cursor cursor = mContext.getContentResolver().query(uri, null, null, null, null);
        String text = null;
        if (cursor.moveToNext()) {
            text = cursor.getString(cursor.getColumnIndex(MediaStore.MediaColumns.DATA));
        }
        cursor.close();
        return text;
    }

    private static Uri contentUri;

    public static void scanFile(Uri uri) {
        if (uri == null) {
            log.error("scanFile: uri is null!");
            return;
        }
        MediaScannerConnection.scanFile(mContext, new String[]{ uri.getPath() },
                null, // mimetypes
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        // uri is in format content://media/external_primary/video/media/##
                        contentUri = uri;
                        log.debug("scanFile: contentUri " + contentUri + " for path " + path);
                    }
                });
    }

    public static boolean isVideoFile(Uri uri) {
        if (uri == null) return false;
        String uriPath = uri.getPath().toLowerCase(Locale.ROOT);
        String mimeType = URLConnection.guessContentTypeFromName(uriPath);
        return mimeType != null && mimeType.startsWith("video");
    }

    public static boolean isImageFile(Uri uri) {
        if (uri == null) return false;
        String uriPath = uri.getPath().toLowerCase(Locale.ROOT);
        String mimeType = URLConnection.guessContentTypeFromName(uriPath);
        return mimeType != null && mimeType.startsWith("image");
    }

    public static boolean isAudioFile(Uri uri) {
        if (uri == null) return false;
        String uriPath = uri.getPath().toLowerCase(Locale.ROOT);
        String mimeType = URLConnection.guessContentTypeFromName(uriPath);
        return mimeType != null && mimeType.startsWith("audio");
    }

    public static Uri getContentUri(Uri uri) {
        contentUri = null;
        File fileToDelete = new File(uri.getPath());
        if (!fileToDelete.exists()) {
            log.debug("getContentUri: file " + uri + " does not exist: easy job!");
            return null;
        } else {
            log.debug("getContentUri: file " + uri + " exists");
        }
        ContentResolver contentResolver = mContext.getContentResolver();
        boolean isVideo = isVideoFile(uri);
        boolean isImage = isImageFile(uri);
        boolean isAudio = isAudioFile(uri);
        Uri mediaUri;
        String[] projection;
        String selection;
        Cursor cursor;
        if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            } else {
                mediaUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            }
            projection = new String[]{MediaStore.Video.Media._ID};
            selection = MediaStore.Video.Media.DATA + "=?";
        } else if (isImage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            } else {
                mediaUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            }
            projection = new String[]{MediaStore.Images.Media._ID};
            selection = MediaStore.Images.Media.DATA + "=?";
        } else if (isAudio) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaUri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
            } else {
                mediaUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            }
            projection = new String[]{MediaStore.Audio.Media._ID};
            selection = MediaStore.Audio.Media.DATA + "=?";
        } else {
            // content://media/external/file/## uri
            mediaUri = MediaStore.Files.getContentUri("external");
            projection = new String[] { MediaStore.MediaColumns._ID };
            selection = MediaStore.MediaColumns.DATA + "=?";
        }
        cursor = contentResolver.query(mediaUri, projection, selection,
                new String[] {uri.getPath()}, null);
        int id;
        if (cursor != null && cursor.moveToFirst()) {
            if (isVideo) {
                id = cursor.getInt(cursor.getColumnIndex(MediaStore.Video.VideoColumns._ID));
                contentUri = Uri.withAppendedPath(mediaUri, "" + id);
            } else if (isImage) {
                id = cursor.getInt(cursor.getColumnIndex(MediaStore.Images.ImageColumns._ID));
                contentUri = Uri.withAppendedPath(mediaUri, "" + id);
            } else if (isAudio) {
                id = cursor.getInt(cursor.getColumnIndex(MediaStore.Audio.AudioColumns._ID));
                contentUri = Uri.withAppendedPath(mediaUri, "" + id);
            } else {
                //id = cursor.getInt(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
                id = cursor.getInt(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
                contentUri = MediaStore.Files.getContentUri("external",id);
            }
            cursor.close();
        }
        log.debug("getContentUri: contentResolver " + contentUri);
        return contentUri;
    }
}