package app.pub.hardware

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * System picker contracts of the files capability (Storage Access Framework). They ask only for openable documents,
 * so online-only documents without a byte stream (such as Google Docs files) are not offered.
 */
object FilePickerContracts {
    class OpenFile : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
    }

    class OpenFiles : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
    }

    /** Saves a new document the user names and places: the input is its MIME type and suggested name. */
    class CreateFile : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.first).putExtra(Intent.EXTRA_TITLE, input.second)
        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}
