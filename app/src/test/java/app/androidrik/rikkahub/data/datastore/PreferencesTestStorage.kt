package app.androidrik.rikkahub.data.datastore

import androidx.datastore.core.Storage
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/** Real protobuf files and atomic replacement on the JVM, including Windows.
 * DataStore's Android FileStorage delegates replacement to File.renameTo, which is POSIX-specific.
 */
internal fun preferencesTestStorage(file: File): Storage<Preferences> = OkioStorage(
    fileSystem = FileSystem.SYSTEM,
    serializer = PreferencesSerializer,
    producePath = { file.toOkioPath() },
)
