package com.android.kidsdrawingapp

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.get
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private val openGalleryLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val ivBackground: ImageView = findViewById(R.id.iv_background)
                ivBackground.setImageURI(result.data?.data)
            }
        }

    private val externalStorageResultLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            permissions.entries.forEach {
                if (it.value) {
                    Toast.makeText(
                        this, "Permission granted for externalStorage", Toast.LENGTH_LONG
                    ).show()

                    if (it.key == Manifest.permission.READ_EXTERNAL_STORAGE) {
                        val pickIntent =
                            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        openGalleryLauncher.launch(pickIntent)
                    }
                } else {
                    Toast.makeText(this, "Permission denied for ${it.key}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }

    var drawingView: DrawingView? = null
    var mImageButtonCurrentPaint: ImageButton? = null
    var customProgressDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawing_view)
        drawingView?.setBrushSize(20.toFloat())

        val linearLayoutPaintColors = findViewById<LinearLayout>(R.id.ll_paint_colors)

        mImageButtonCurrentPaint = linearLayoutPaintColors[1] as ImageButton
        mImageButtonCurrentPaint?.setImageDrawable(
            ContextCompat.getDrawable(
                this, R.drawable.pallet_pressed
            )
        )

        val ibBrush: ImageButton = findViewById(R.id.ib_brush)
        ibBrush.setOnClickListener {
            showBrushSizeChooserDialog()
        }

        val ibUndo: ImageButton = findViewById(R.id.ib_undo)
        ibUndo.setOnClickListener {
            drawingView?.onClickUndo()
        }

        val ibSave: ImageButton = findViewById(R.id.ib_save)
        ibSave.setOnClickListener {
            showProgressDialog()
            saveView()
        }

        val ibGallery: ImageButton = findViewById(R.id.ib_gallery)
        ibGallery.setOnClickListener {
            requestStoragePermission()
        }
    }

    private fun saveView() {
        if (isReadStorageAllowed()) {
            lifecycleScope.launch {
                val flDrawingViewContainer: FrameLayout =
                    findViewById(R.id.fl_drawing_view_container)
                val bitmap = getBitmapFromView(flDrawingViewContainer)
                saveBitmapFile(bitmap)
            }
        } else {
            Toast.makeText(
                this@MainActivity,
                "${Manifest.permission.READ_EXTERNAL_STORAGE} permission is not granted.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isReadStorageAllowed(): Boolean {
        val result =
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        return result === PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showRationaleDialog(
                    "Permission demo required externalStorage access",
                    "Camera can't be used because ExternalStorage access is denied"
                )
            } else {
                externalStorageResultLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    private fun showRationaleDialog(title: String, message: String) {
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle(title).setMessage(message).setPositiveButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
    }

    private fun showBrushSizeChooserDialog() {
        val brushDialog = Dialog(this)
        brushDialog.setContentView(R.layout.dialog_brush_size)
        brushDialog.setTitle("Brush size: ")

        setIbButtonClickListener(brushDialog, R.id.ib_small_button, 10.toFloat())
        setIbButtonClickListener(brushDialog, R.id.ib_medium_button, 20.toFloat())
        setIbButtonClickListener(brushDialog, R.id.ib_large_button, 30.toFloat())

        brushDialog.show()
    }

    private fun setIbButtonClickListener(
        brushDialog: Dialog, ibButton: Int, brushSize: Float
    ) {
        val button = brushDialog.findViewById<ImageButton>(ibButton)
        button.setOnClickListener {
            drawingView!!.setBrushSize(brushSize)
            brushDialog.dismiss()
        }
    }

    fun paintClicked(view: View) {
        if (mImageButtonCurrentPaint !== view) {
            val imageButton = view as ImageButton
            val colorTag = imageButton.tag.toString()
            drawingView?.setColor(colorTag)

            imageButton.setImageDrawable(
                ContextCompat.getDrawable(
                    this, R.drawable.pallet_pressed
                )
            )
            mImageButtonCurrentPaint?.setImageDrawable(
                ContextCompat.getDrawable(
                    this, R.drawable.pallet_normal
                )
            )

            mImageButtonCurrentPaint = view
        }
    }

    private fun getBitmapFromView(view: View): Bitmap {
        val returnedBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(returnedBitmap)
        val bgDrawable = view.background
        if (bgDrawable != null) {
            bgDrawable.draw(canvas)
        } else {
            canvas.drawColor(Color.WHITE)
        }

        view.draw(canvas)

        return returnedBitmap
    }

    private suspend fun saveBitmapFile(mBitmap: Bitmap?): String {
        var result = ""
        withContext(Dispatchers.IO) {
            if (mBitmap != null) {
                try {
                    val bytesOutputStream = ByteArrayOutputStream()
                    mBitmap.compress(Bitmap.CompressFormat.PNG, 90, bytesOutputStream)

                    val f =
                        File(externalCacheDir?.absoluteFile.toString() + File.separator + "KidsDrawingApp_" + System.currentTimeMillis() / 1000 + ".png")
                    val fo = FileOutputStream(f)
                    fo.write(bytesOutputStream.toByteArray())
                    fo.close()

                    result = f.absolutePath

                    runOnUiThread {
                        cancelProgressDialog()
                        if (result.isNotEmpty()) {
                            Toast.makeText(
                                this@MainActivity,
                                "File saved successfully: $result",
                                Toast.LENGTH_LONG
                            ).show()
                            shareImage(result)
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Something went wrong while saving the file.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    result = ""
                    e.printStackTrace()
                }
            }
        }
        return result
    }

    private fun showProgressDialog() {
        customProgressDialog = Dialog(this@MainActivity)
        customProgressDialog?.setContentView(R.layout.dialog_custom_progress)
        customProgressDialog?.show()
    }

    private fun cancelProgressDialog() {
        if (customProgressDialog != null) {
            customProgressDialog?.dismiss()
            customProgressDialog = null
        }
    }

    private fun shareImage(imageFilePath: String) {
        MediaScannerConnection.scanFile(this, arrayOf(imageFilePath), null) { path, uri ->
            val shareIntent = Intent()
            shareIntent.action = Intent.ACTION_SEND
            shareIntent.type = "image/png"
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
            startActivity(Intent.createChooser(shareIntent, "Share"))
        }
    }
}