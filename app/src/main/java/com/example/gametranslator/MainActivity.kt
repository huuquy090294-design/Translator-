package com.example.gametranslator

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.*

class MainActivity : Activity() {
    private lateinit var projMgr: MediaProjectionManager
    private lateinit var edtApiKey: EditText
    private lateinit var edtContext: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val prefs = getSharedPreferences("config", MODE_PRIVATE)

        // Tạo giao diện bằng code (không cần file XML layout)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "GAMINIK AI FREE"
            textSize = 22f
            setTextColor(Color.parseColor("#00FF9D"))
        }

        edtApiKey = EditText(this).apply {
            hint = "Dán Gemini API Key (aistudio.google.com) vào đây"
            setText(prefs.getString("api_key", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        edtContext = EditText(this).apply {
            hint = "Ngữ cảnh game (VD: Game RPG tiếng Anh)"
            setText(prefs.getString("context", "Game RPG tiếng Anh, giữ thuật ngữ kỹ năng, xưng hô tự nhiên."))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val btnStart = Button(this).apply {
            text = "BẬT KHUNG DỊCH LÊN MÀN HÌNH"
            setBackgroundColor(Color.parseColor("#2EA043"))
            setTextColor(Color.WHITE)
            setOnClickListener { startTranslator() }
        }

        val btnStop = Button(this).apply {
            text = "TẮT KHUNG DỊCH"
            setBackgroundColor(Color.parseColor("#D73A49"))
            setTextColor(Color.WHITE)
            setOnClickListener { stopService(Intent(this@MainActivity, FloatingTranslateService::class.java)) }
        }

        layout.addView(title)
        layout.addView(edtApiKey)
        layout.addView(edtContext)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)
    }

    private fun startTranslator() {
        val key = edtApiKey.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Vui lòng nhập Gemini API Key!", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("config", MODE_PRIVATE).edit()
            .putString("api_key", key)
            .putString("context", edtContext.text.toString().trim())
            .apply()

        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Hãy cấp quyền Hiển thị trên ứng dụng khác!", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(projMgr.createScreenCaptureIntent(), 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, FloatingTranslateService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            startForegroundService(svc)
            moveTaskToBack(true) // Ẩn app xuống để vào Game
        }
    }
}

class FloatingTranslateService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var frameLayout: LinearLayout
    private lateinit var tvResult: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val history = mutableListOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startEnforcedForeground()
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY

        val projMgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, data)
        setupVirtualDisplay()
        if (!::wm.isInitialized) setupFloatingBox()
        return START_NOT_STICKY
    }

    private fun startEnforcedForeground() {
        val channelId = "game_trans_channel"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Game Translator", NotificationManager.IMPORTANCE_LOW))
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Khung dịch AI đang chạy")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        startForeground(1, notif)
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCap", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    private fun setupFloatingBox() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            850, 320,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 400
        }

        frameLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#B3000000")) // Đen trong suốt 70%
        }

        // Thanh điều khiển phía trên khung chọn
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1F6FEB"))
            setPadding(16, 8, 16, 8)
        }

        val btnMove = TextView(this).apply {
            text = "✥ Kéo "
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        val btnTrans = Button(this).apply {
            text = "DỊCH VÙNG NÀY"
            setBackgroundColor(Color.parseColor("#2EA043"))
            setTextColor(Color.WHITE)
            setOnClickListener { captureAndTranslate() }
        }

        val btnClear = Button(this).apply {
            text = "Ẩn chữ"
            setOnClickListener {
                tvResult.text = ""
                frameLayout.setBackgroundColor(Color.parseColor("#3300FF9D")) // Chuyển sang khung ngắm trong suốt
            }
        }

        val btnResize = TextView(this).apply {
            text = " ↘ Kéo rộng"
            setTextColor(Color.YELLOW)
            textSize = 14f
        }

        header.addView(btnMove)
        header.addView(btnTrans)
        header.addView(btnClear)
        header.addView(btnResize)

        tvResult = TextView(this).apply {
            text = "Kéo khung này đè lên hộp thoại Game rồi bấm [DỊCH VÙNG NÀY]"
            setTextColor(Color.parseColor("#00FF9D"))
            textSize = 15f
            setPadding(16, 12, 16, 12)
        }

        frameLayout.addView(header)
        frameLayout.addView(tvResult)
        wm.addView(frameLayout, params)

        // Xử lý kéo di chuyển khung
        btnMove.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View?, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { initX = params.x; initY = params.y; touchX = e.rawX; touchY = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initX + (e.rawX - touchX).toInt()
                        params.y = initY + (e.rawY - touchY).toInt()
                        wm.updateViewLayout(frameLayout, params)
                    }
                }
                return true
            }
        })

        // Xử lý kéo thay đổi kích thước vùng dịch
        btnResize.setOnTouchListener(object : View.OnTouchListener {
            var initW = 0; var initH = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View?, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { initW = params.width; initH = params.height; touchX = e.rawX; touchY = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        params.width = (initW + (e.rawX - touchX).toInt()).coerceAtLeast(350)
                        params.height = (initH + (e.rawY - touchY).toInt()).coerceAtLeast(180)
                        wm.updateViewLayout(frameLayout, params)
                    }
                }
                return true
            }
        })
    }

    private fun captureAndTranslate() {
        scope.launch {
            tvResult.text = "⏳ Đang dịch..."
            // 1. Tạm ẩn khung 100ms để chụp chữ gốc của Game bên dưới
            frameLayout.visibility = View.INVISIBLE
            delay(120)

            val croppedBitmap = withContext(Dispatchers.IO) {
                val img = imageReader?.acquireLatestImage() ?: return@withContext null
                val plane = img.planes[0]
                val width = img.width
                val height = img.height
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBmp = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                fullBmp.copyPixelsFromBuffer(plane.buffer)
                img.close()

                // Cắt đúng tọa độ khung mà người dùng đã kéo chọn
                val loc = IntArray(2)
                frameLayout.getLocationOnScreen(loc)
                val x = loc[0].coerceIn(0, width - 1)
                val y = loc[1].coerceIn(0, height - 1)
                val w = params.width.coerceAtMost(width - x)
                val h = params.height.coerceAtMost(height - y)
                Bitmap.createBitmap(fullBmp, x, y, w, h)
            }

            frameLayout.visibility = View.VISIBLE
            frameLayout.setBackgroundColor(Color.parseColor("#CC000000"))

            if (croppedBitmap == null) {
                tvResult.text = "Không chụp được hình, hãy thử lại!"
                return@launch
            }

            // 2. Gửi ảnh đã cắt cho Gemini 2.5 Flash dịch theo ngữ cảnh
            val prefs = getSharedPreferences("config", MODE_PRIVATE)
            val apiKey = prefs.getString("api_key", "") ?: ""
            val gameCtx = prefs.getString("context", "") ?: ""

            val translated = withContext(Dispatchers.IO) {
                try {
                    val model = GenerativeModel("gemini-2.5-flash", apiKey)
                    val prevChat = history.takeLast(3).joinToString("\n")
                    val prompt = """
                        Đọc chữ tiếng Anh trong ảnh và dịch sang tiếng Việt.
                        Ngữ cảnh game: $gameCtx.
                        Các câu thoại trước đó: $prevChat
                        Chỉ trả về đúng câu dịch tiếng Việt tự nhiên, không giải thích.
                    """.trimIndent()
                    val res = model.generateContent(content {
                        image(croppedBitmap)
                        text(prompt)
                    })
                    res.text?.trim() ?: "Không đọc được chữ trong vùng chọn."
                } catch (e: Exception) {
                    "Lỗi AI: ${e.localizedMessage}"
                }
            }

            history.add(translated)
            tvResult.text = translated
        }
    }

    override fun onDestroy() {
        scope.cancel()
        virtualDisplay?.release()
        mediaProjection?.stop()
        if (::wm.isInitialized) wm.removeView(frameLayout)
        super.onDestroy()
    }
}
