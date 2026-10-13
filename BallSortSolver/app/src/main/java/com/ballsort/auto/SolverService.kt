package com.ballsort.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.ballsort.auto.core.Board
import com.ballsort.auto.core.Move
import com.ballsort.auto.core.Solver
import com.ballsort.auto.core.Vision
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class SolverService : AccessibilityService() {

    companion object {
        const val PREFS = "ballsort"
        const val KEY_DELAY = "delay"
        const val KEY_RUN = "runMode"
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var button: FabView
    private lateinit var status: TextView
    private lateinit var params: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("ballsort-worker").also { it.start() }
    private val worker = Handler(thread.looper)

    @Volatile private var running = false
    @Volatile private var cancel = false

    /** ปุ่มกลมลอย: ▶ ตอนว่าง / ■ ตอนทำงาน */
    private class FabView(ctx: Context) : View(ctx) {
        var busy = false
            set(v) { field = v; invalidate() }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = w / 2f
            bg.color = if (busy) Color.parseColor("#E5484D") else Color.parseColor("#6C4DFF")
            c.drawCircle(r, h / 2, r - 2f, bg)
            if (busy) {
                c.drawRect(w * 0.33f, h * 0.33f, w * 0.67f, h * 0.67f, fg)
            } else {
                val p = Path().apply {
                    moveTo(w * 0.40f, h * 0.30f); lineTo(w * 0.72f, h * 0.50f); lineTo(w * 0.40f, h * 0.70f); close()
                }
                c.drawPath(p, fg)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density
        val size = (58 * dp).toInt()

        button = FabView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setBackgroundColor(Color.parseColor("#CC1A1040"))
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            visibility = View.GONE
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(button, LinearLayout.LayoutParams(size, size))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (12 * dp).toInt(); y = (180 * dp).toInt() }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        button.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); wm.updateViewLayout(root, params) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onButtonClick()
            }
            true
        }
        wm.addView(root, params)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { cancel = true }

    override fun onDestroy() {
        cancel = true
        try { wm.removeView(root) } catch (_: Exception) {}
        thread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI helpers

    private fun ui(block: () -> Unit) { main.post(block) }

    private fun say(msg: String?) = ui {
        if (msg == null) status.visibility = View.GONE else { status.text = msg; status.visibility = View.VISIBLE }
    }

    private fun setBusy(b: Boolean) = ui { button.busy = b }

    private fun setOverlayVisible(v: Boolean) {
        val latch = CountDownLatch(1)
        ui { root.visibility = if (v) View.VISIBLE else View.INVISIBLE; latch.countDown() }
        latch.await(1, TimeUnit.SECONDS)
        if (!v) Thread.sleep(180)
    }

    private fun onButtonClick() {
        if (running) { cancel = true; say("กำลังหยุด..."); return }
        running = true; cancel = false; setBusy(true)
        worker.post {
            try { solveAndRun() }
            catch (e: Exception) { say("ผิดพลาด: ${e.message}") }
            finally { running = false; setBusy(false); setOverlayVisible(true) }
        }
    }

    // ---------------------------------------------------------------- screen / gestures

    private fun capture(): Bitmap? {
        repeat(4) {
            val latch = CountDownLatch(1)
            var result: Bitmap? = null
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    try {
                        val hb = s.hardwareBuffer
                        result = Bitmap.wrapHardwareBuffer(hb, s.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                    } finally { latch.countDown() }
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
            latch.await(4, TimeUnit.SECONDS)
            if (result != null) return result
            Thread.sleep(450) // takeScreenshot จำกัดความถี่
        }
        return null
    }

    private fun readBoard(palette: MutableList<IntArray>): Board? {
        setOverlayVisible(false)
        val shot = try { capture() } finally { setOverlayVisible(true) }
        val bmp = shot ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return Vision.read(px, w, h, palette)
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val latch = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    private fun tapTube(b: Board, idx: Int): Boolean {
        val t = b.tubes[idx]
        return tap(t.cx, t.cy)
    }

    // ---------------------------------------------------------------- main flow

    private fun solveAndRun() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val delay = prefs.getInt(KEY_DELAY, 450).toLong()
        var runMode = prefs.getBoolean(KEY_RUN, false)
        val palette = mutableListOf<IntArray>()   // ใช้ซ้ำทุกการสแกน เพื่อให้ id สีคงที่

        for (attempt in 1..5) {
            if (cancel) { say("หยุดแล้ว"); return }
            say("กำลังอ่านกระดาน...")
            val board = readBoard(palette)
            if (board == null || board.tubes.size < 2) { say("อ่านกระดานไม่ได้ (เปิดหน้าเกมไว้หรือยัง?)"); return }
            val state = board.balls
            val caps = board.caps
            if (Solver.isGoal(state)) { say("เรียบร้อยแล้ว ✓"); return }

            say("พบ ${board.tubes.size} แก้ว ${palette.size} สี — กำลังคิด...")
            val plan: List<Move> = Solver.solve(state, caps, runMode) ?: run { say("ไม่พบวิธีแก้ (อ่านสีผิด?)"); return }

            var cur = state
            var replan = false
            for ((i, mv) in plan.withIndex()) {
                if (cancel) { say("หยุดแล้ว"); return }
                say("ขั้น ${i + 1}/${plan.size}")
                if (!tapTube(board, mv.from)) { say("แตะไม่สำเร็จ"); return }
                Thread.sleep(delay)
                if (!tapTube(board, mv.to)) { say("แตะไม่สำเร็จ"); return }
                Thread.sleep(delay + 250)
                val prev = cur
                cur = Solver.apply(cur, caps, mv, runMode)

                // ตรวจผลหลังขั้นแรก (และทุก 8 ขั้น): ถ้าไม่ตรงกับที่คาด ให้ปรับโหมดแล้วคิดใหม่จากสถานะจริง
                if (i == 0 || (i + 1) % 8 == 0) {
                    Thread.sleep(250)
                    val now = readBoard(palette)
                    if (now != null && now.balls != cur) {
                        if (now.balls == Solver.apply(prev, caps, mv, !runMode)) {
                            runMode = !runMode
                            prefs.edit().putBoolean(KEY_RUN, runMode).apply()
                        }
                        replan = true
                        break
                    }
                }
            }
            if (!replan) {
                Thread.sleep(400)
                val fin = readBoard(palette)
                if (fin != null && Solver.isGoal(fin.balls)) { say("สำเร็จ ✓ (${plan.size} ขั้น)"); return }
                if (fin == null) { say("เสร็จสิ้น"); return }
            }
        }
        say("ยังไม่สำเร็จ ลองกดอีกครั้ง")
    }
}
