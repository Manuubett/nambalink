package com.manuubett.nambalink

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val teal = Color.parseColor("#0E7490")
    private val green = Color.parseColor("#15803D")
    private val red = Color.parseColor("#DC2626")
    private val ink = Color.parseColor("#0F172A")
    private val grey = Color.parseColor("#64748B")
    private val slate = Color.parseColor("#475569")
    private val bg = Color.parseColor("#F1F5F9")

    private val codePng = 21
    private val codePdf = 22

    private lateinit var prefs: SharedPreferences
    private lateinit var db: Db
    private lateinit var gate: PayGate
    private lateinit var root: LinearLayout

    private var screen = "main"
    private var backTo = "main"
    private var viewing: Person? = null
    private var found: List<String> = emptyList()

    private var allowed = false
    private var checking = true
    private var lastGateAt = 0L
    private var downloadId = -1L
    private var updateText = "Check for updates"
    private var pendingBytes: ByteArray? = null

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id == downloadId && id != -1L) installDownloaded()
        }
    }

    private class Field(val hint: String, val initial: String = "", val kind: Int = TEXT) {
        companion object {
            const val TEXT = 0
            const val PHONE = 1
            const val NUMBER = 2
            const val PLAIN = 3
            const val MULTI = 4
        }
    }

    // "" until the user chooses, then "personal" or "business"
    private val mode: String get() = prefs.getString("mode", "") ?: ""
    private val isBusiness: Boolean get() = mode == "business"

    // ---- Life cycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("nambalink_settings", Context.MODE_PRIVATE)
        db = Db(this)
        gate = makeGate()

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.isFillViewport = true

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))

        scroll.addView(root)
        setContentView(scroll)

        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(downloadReceiver, filter)
            }
        } catch (e: Exception) {
            // The download still happens; the user opens it from notifications
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        ensureGate(false)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {
            // Not registered
        }

        super.onDestroy()
    }

    override fun onBackPressed() {
        if (screen != "main") {
            screen = backTo
            backTo = "main"
            render()
        } else {
            super.onBackPressed()
        }
    }

    // ---- Subscription (Personal and Business have separate plans) ----

    private fun makeGate(): PayGate =
        PayGate(this, appId = if (isBusiness) "nambalinkbiz" else "nambalink")

    private fun ensureGate(force: Boolean) {
        val now = System.currentTimeMillis()

        if (mode.isEmpty()) return
        if (!force && allowed && now - lastGateAt < 30 * 60_000L) return

        checking = true
        render()

        gate.check(
            onDenied = {
                allowed = false
                checking = false
                render()
            },
            onAllowed = {
                allowed = true
                checking = false
                lastGateAt = System.currentTimeMillis()
                render()
            }
        )
    }

    private fun setMode(newMode: String) {
        prefs.edit().putString("mode", newMode).apply()
        gate = makeGate()
        allowed = false
        checking = true
        screen = "main"
        ensureGate(true)
    }

    // ---- Drawing ----

    private fun render() {
        root.removeAllViews()

        title()

        if (mode.isEmpty()) {
            chooseMode()
            footer()
            return
        }

        if (checking && !allowed) {
            note("Checking your subscription…")
            footer()
            return
        }

        if (!allowed) {
            val c = card()
            c.addView(heading("Subscription needed"))
            c.addView(body("Your free trial or subscription has ended. Your saved numbers are safe on this phone."))
            c.addView(button("Subscribe or renew", teal) { ensureGate(true) })
            footer()
            return
        }

        when (screen) {
            "person" -> personScreen()
            "saved" -> savedScreen()
            "found" -> foundScreen()
            "settings" -> settingsScreen()
            else -> mainScreen()
        }

        footer()
    }

    private fun title() {
        val t = TextView(this)
        t.text = "NambaLink"
        t.textSize = 26f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(teal)
        root.addView(t)

        val s = TextView(this)
        s.text = when (mode) {
            "business" -> "Business · scan, share and get paid"
            "personal" -> "Personal · scan and share numbers"
            else -> "Share and scan phone numbers"
        }
        s.textSize = 14f
        s.setTextColor(grey)
        s.setPadding(0, 0, 0, dp(12))
        root.addView(s)
    }

    private fun backBar(label: String) {
        val b = Button(this)
        b.text = "←  $label"
        b.isAllCaps = false
        b.setTextColor(teal)
        b.textSize = 16f
        b.setBackgroundColor(Color.TRANSPARENT)
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        b.setPadding(0, 0, 0, 0)
        b.setOnClickListener {
            screen = backTo
            backTo = "main"
            render()
        }
        root.addView(b, LinearLayout.LayoutParams(-2, dp(44)))
    }

    // ---- First run: Personal or Business ----

    private fun chooseMode() {
        val c = card()
        c.addView(heading("How will you use NambaLink?"))
        c.addView(body("You can change this later in Settings. Each plan has its own free trial and price."))

        c.addView(
            button("Personal", teal) { setMode("personal") }
        )
        c.addView(body("Your own QR code with your name and number. Scan numbers and save them."))

        c.addView(
            button("Business", green) { setMode("business") }
        )
        c.addView(body("Everything in Personal, plus your business name, Till or Paybill on a printable poster for customers."))
    }

    // ---- Main screen ----

    private fun mainScreen() {
        val scan = card()
        scan.addView(heading("Scan a QR code"))
        scan.addView(body("Works with NambaLink codes and other phone-number QR codes."))
        scan.addView(button("Scan now", teal) { scanQr() })

        val nums = card()
        nums.addView(heading("Get a number"))
        nums.addView(body("Paste a message or type a number, even if it has spaces or dashes."))
        nums.addView(button("Type or paste a number", slate) { typeNumbers() })
        nums.addView(button("Use copied text", slate) { useClipboard() })

        val mine = card()
        mine.addView(heading("My QR code"))

        val me = myProfile()

        if (me == null) {
            mine.addView(
                body(
                    if (isBusiness) {
                        "Add your business name and phone. Add a Till or Paybill so customers can pay you."
                    } else {
                        "Add your name and phone to get your own QR code."
                    }
                )
            )
            mine.addView(button("Create my profile", green) { editProfile() })
        } else {
            val image = ImageView(this)
            image.setImageBitmap(qrBitmap(Namba.vCard(me), 700))
            image.setBackgroundColor(Color.WHITE)
            mine.addView(image, LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })

            val name = TextView(this)
            name.text = me.title
            name.textSize = 20f
            name.setTypeface(null, Typeface.BOLD)
            name.setTextColor(ink)
            name.gravity = Gravity.CENTER
            name.setPadding(0, dp(8), 0, 0)
            mine.addView(name)

            val phone = TextView(this)
            phone.text = Namba.pretty(me.phone)
            phone.textSize = 16f
            phone.setTextColor(grey)
            phone.gravity = Gravity.CENTER
            mine.addView(phone)

            if (me.hasPayment) {
                val pay = TextView(this)
                pay.text = paymentLine(me)
                pay.textSize = 14f
                pay.setTextColor(grey)
                pay.gravity = Gravity.CENTER
                mine.addView(pay)
            }

            mine.addView(button("Save poster to print (PDF)", green) { savePoster(me, true) })
            mine.addView(button("Save poster as picture", slate) { savePoster(me, false) })
            mine.addView(button("Save QR code only (for stickers)", slate) { saveQrOnly(me) })
            mine.addView(button("Edit my profile", slate) { editProfile() })
        }

        val more = card()
        val count = db.all().size
        more.addView(button("Saved numbers ($count)", slate) { screen = "saved"; backTo = "main"; render() })
        more.addView(button("Settings", slate) { screen = "settings"; backTo = "main"; render() })
    }

    private fun paymentLine(p: Person): String {
        val parts = ArrayList<String>()

        if (p.till.isNotEmpty()) parts.add("Till " + p.till)

        if (p.paybill.isNotEmpty()) {
            parts.add("Paybill " + p.paybill + (if (p.acc.isNotEmpty()) " · Acc " + p.acc else ""))
        }

        return parts.joinToString("   ")
    }

    // ---- My profile ----

    private fun myProfile(): Person? {
        val name = prefs.getString("p_name", "") ?: ""
        val org = prefs.getString("p_org", "") ?: ""
        val phone = prefs.getString("p_phone", "") ?: ""

        if (phone.isEmpty()) return null
        if (isBusiness && org.isEmpty()) return null
        if (!isBusiness && name.isEmpty()) return null

        return Person(
            0L,
            name,
            if (isBusiness) org else "",
            phone,
            if (isBusiness) prefs.getString("p_till", "") ?: "" else "",
            if (isBusiness) prefs.getString("p_paybill", "") ?: "" else "",
            if (isBusiness) prefs.getString("p_acc", "") ?: "" else ""
        )
    }

    private fun editProfile() {
        val fields = ArrayList<Field>()

        if (isBusiness) {
            fields.add(Field("Business name", prefs.getString("p_org", "") ?: "", Field.TEXT))
            fields.add(Field("Contact person (optional)", prefs.getString("p_name", "") ?: "", Field.TEXT))
        } else {
            fields.add(Field("Your name", prefs.getString("p_name", "") ?: "", Field.TEXT))
        }

        val savedPhone = prefs.getString("p_phone", "") ?: ""
        fields.add(Field("Phone number", if (savedPhone.isEmpty()) "" else Namba.pretty(savedPhone), Field.PHONE))

        if (isBusiness) {
            fields.add(Field("Till number (optional)", prefs.getString("p_till", "") ?: "", Field.NUMBER))
            fields.add(Field("Paybill number (optional)", prefs.getString("p_paybill", "") ?: "", Field.NUMBER))
            fields.add(Field("Paybill account (optional)", prefs.getString("p_acc", "") ?: "", Field.PLAIN))
        }

        form(if (isBusiness) "Business profile" else "My profile", fields, "Save") { v ->
            var i = 0
            val org = if (isBusiness) v[i++].trim() else ""
            val name = v[i++].trim()
            val phone = Namba.normalize(v[i++])
            val till = if (isBusiness) v[i++].trim() else ""
            val bill = if (isBusiness) v[i++].trim() else ""
            val acc = if (isBusiness) v[i].trim() else ""

            when {
                isBusiness && org.isEmpty() -> "Enter the business name"
                !isBusiness && name.isEmpty() -> "Enter your name"
                phone == null -> "Enter a valid Kenyan number, for example 0712 345 678"
                till.isNotEmpty() && (till.length < 4 || till.length > 8) -> "A Till number has 4 to 8 digits"
                bill.isNotEmpty() && (bill.length < 4 || bill.length > 8) -> "A Paybill number has 4 to 8 digits"
                else -> {
                    prefs.edit()
                        .putString("p_org", org)
                        .putString("p_name", name)
                        .putString("p_phone", phone)
                        .putString("p_till", till)
                        .putString("p_paybill", bill)
                        .putString("p_acc", acc)
                        .apply()

                    render()
                    null
                }
            }
        }
    }

    // ---- Scanning and finding numbers ----

    private fun scanQr() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()

            GmsBarcodeScanning.getClient(this, options)
                .startScan()
                .addOnSuccessListener { barcode -> handleScanned(barcode.rawValue ?: "") }
                .addOnFailureListener {
                    toast(
                        "The scanner could not open. Update Google Play services, or scan with " +
                            "your phone's camera app, copy the text and tap Use copied text."
                    )
                }
        } catch (e: Exception) {
            toast("The scanner is not available on this phone.")
        }
    }

    private fun handleScanned(raw: String) {
        val card = Namba.parseCard(raw)

        if (card != null) {
            openPerson(card, "main")
            return
        }

        val numbers = Namba.extractNumbers(raw)

        if (numbers.isNotEmpty()) {
            showNumbers(numbers)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("This QR code has no phone number")
            .setMessage(
                raw.take(300) +
                    (if (raw.length > 300) "…" else "") +
                    "\n\nNambaLink never opens links from a QR code for you."
            )
            .setPositiveButton("Copy text") { _, _ -> copy(raw) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun typeNumbers() {
        form(
            "Type or paste a number",
            listOf(Field("e.g. 0712 345 678", "", Field.MULTI)),
            "Find"
        ) { v ->
            val numbers = Namba.extractNumbers(v[0])

            if (numbers.isEmpty()) {
                "No Kenyan phone number found in that text"
            } else {
                showNumbers(numbers)
                null
            }
        }
    }

    private fun useClipboard() {
        val text = try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip

            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }

        if (text.isEmpty()) {
            toast("Nothing copied yet. Copy a message or number first.")
            return
        }

        val numbers = Namba.extractNumbers(text)

        if (numbers.isEmpty()) {
            toast("No Kenyan phone number found in the copied text.")
        } else {
            showNumbers(numbers)
        }
    }

    private fun showNumbers(numbers: List<String>) {
        if (numbers.size == 1) {
            openPerson(Person(0L, "", "", numbers[0], "", "", ""), "main")
        } else {
            found = numbers
            screen = "found"
            backTo = "main"
            render()
        }
    }

    private fun openPerson(p: Person, back: String) {
        viewing = p
        screen = "person"
        backTo = back
        render()
    }

    // ---- A person or business ----

    private fun personScreen() {
        val p = viewing

        if (p == null) {
            screen = "main"
            render()
            return
        }

        backBar("Back")

        val savedId = db.idForPhone(p.phone)

        val c = card()

        val name = TextView(this)
        name.text = p.title
        name.textSize = 22f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(ink)
        c.addView(name)

        if (p.org.isNotEmpty() && p.name.isNotEmpty()) c.addView(body(p.name))

        val phone = TextView(this)
        phone.text = Namba.pretty(p.phone)
        phone.textSize = 28f
        phone.setTypeface(null, Typeface.BOLD)
        phone.setTextColor(teal)
        phone.setPadding(0, dp(4), 0, dp(8))
        c.addView(phone)

        c.addView(button("Call", green) { dial(p.phone) })
        c.addView(button("Send SMS", slate) { sms(p.phone) })
        c.addView(button("WhatsApp", slate) { whatsapp(p.phone) })
        c.addView(button("Save to phone contacts", slate) { insertContact(p) })
        c.addView(button("Copy number", slate) { copy(p.phone.removePrefix("+")) })

        if (savedId > 0L) {
            c.addView(button("Remove from my list", red) {
                db.delete(savedId)
                toast("Removed")
                render()
            })
        } else {
            c.addView(button("Keep in my list", teal) {
                db.save(p)
                toast("Saved in NambaLink")
                render()
            })
        }

        if (p.hasPayment) {
            val pay = card()
            pay.addView(heading("Pay ${p.title}"))

            if (p.till.isNotEmpty()) {
                pay.addView(body("Till number: ${p.till}"))
                pay.addView(button("Copy Till number", slate) { copy(p.till) })
            }

            if (p.paybill.isNotEmpty()) {
                pay.addView(
                    body("Paybill: ${p.paybill}" + (if (p.acc.isNotEmpty()) "\nAccount: ${p.acc}" else ""))
                )
                pay.addView(button("Copy Paybill number", slate) { copy(p.paybill) })

                if (p.acc.isNotEmpty()) {
                    pay.addView(button("Copy account", slate) { copy(p.acc) })
                }
            }

            val warn = TextView(this)
            warn.text = "Before you pay, check that the name M-PESA shows is ${p.title}. " +
                "If it is different, do not pay. Someone may have stuck a fake code on top of the real one."
            warn.textSize = 13f
            warn.setTextColor(red)
            warn.setPadding(0, dp(8), 0, 0)
            pay.addView(warn)
        }
    }

    private fun dial(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (e: Exception) {
            toast("Could not open the dialer")
        }
    }

    private fun sms(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")))
        } catch (e: Exception) {
            toast("Could not open messages")
        }
    }

    private fun whatsapp(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone.removePrefix("+"))))
        } catch (e: Exception) {
            toast("Could not open WhatsApp")
        }
    }

    private fun insertContact(p: Person) {
        try {
            val i = Intent(ContactsContract.Intents.Insert.ACTION)
            i.type = ContactsContract.RawContacts.CONTENT_TYPE
            i.putExtra(ContactsContract.Intents.Insert.NAME, if (p.name.isNotEmpty()) p.name else p.title)
            i.putExtra(ContactsContract.Intents.Insert.PHONE, p.phone)

            if (p.org.isNotEmpty()) i.putExtra(ContactsContract.Intents.Insert.COMPANY, p.org)
            if (p.hasPayment) i.putExtra(ContactsContract.Intents.Insert.NOTES, "Pay: " + paymentLine(p))

            startActivity(i)
        } catch (e: Exception) {
            toast("Could not open Contacts")
        }
    }

    private fun copy(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NambaLink", text))
            toast("Copied")
        } catch (e: Exception) {
            toast("Could not copy")
        }
    }

    // ---- Lists ----

    private fun savedScreen() {
        backBar("Back")

        val list = db.all()
        val c = card()
        c.addView(heading("Saved numbers (${list.size})"))

        if (list.isEmpty()) {
            c.addView(body("Numbers you keep from a scan or paste will appear here."))
            return
        }

        for (p in list) {
            c.addView(personRow(p) { openPerson(p, "saved") })
        }
    }

    private fun foundScreen() {
        backBar("Back")

        val c = card()
        c.addView(heading("${found.size} numbers found"))
        c.addView(body("Tap a number to call, message or save it."))

        for (n in found) {
            val p = Person(0L, "", "", n, "", "", "")
            c.addView(personRow(p) { openPerson(p, "found") })
        }

        c.addView(button("Keep all in my list", teal) {
            for (n in found) {
                if (db.idForPhone(n) == 0L) db.save(Person(0L, "", "", n, "", "", ""))
            }

            toast("Saved ${found.size} numbers")
            screen = "saved"
            backTo = "main"
            render()
        })
    }

    private fun personRow(p: Person, click: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(10), 0, dp(10))

        val top = TextView(this)
        top.text = if (p.name.isEmpty() && p.org.isEmpty()) Namba.pretty(p.phone) else p.title
        top.textSize = 16f
        top.setTypeface(null, Typeface.BOLD)
        top.setTextColor(ink)
        row.addView(top)

        if (p.name.isNotEmpty() || p.org.isNotEmpty()) {
            val sub = TextView(this)
            sub.text = Namba.pretty(p.phone)
            sub.textSize = 13f
            sub.setTextColor(grey)
            row.addView(sub)
        }

        row.setOnClickListener { click() }

        return row
    }

    // ---- Settings ----

    private fun settingsScreen() {
        backBar("Settings")

        val c = card()
        c.addView(heading("Plan"))
        c.addView(body("You are using the ${if (isBusiness) "Business" else "Personal"} plan."))

        c.addView(
            button(if (isBusiness) "Switch to Personal" else "Switch to Business", slate) {
                AlertDialog.Builder(this)
                    .setTitle(if (isBusiness) "Switch to Personal?" else "Switch to Business?")
                    .setMessage(
                        "Each plan has its own subscription. Your saved numbers stay on this phone. " +
                            if (isBusiness) {
                                "Business features such as the payment poster will be switched off."
                            } else {
                                "You will start the Business free trial if you have not used it."
                            }
                    )
                    .setPositiveButton("Switch") { _, _ ->
                        setMode(if (isBusiness) "personal" else "business")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        val u = card()
        u.addView(heading("App"))
        u.addView(button(updateText, slate) { checkUpdate() })

        u.addView(button("How to use", slate) {
            AlertDialog.Builder(this)
                .setTitle("How to use")
                .setMessage(
                    "To share your number: create your profile, then print the poster or show the QR code on your screen.\n\n" +
                        "To get a number: tap Scan now and point at a QR code. Then call, message, save it, or copy the Till or Paybill.\n\n" +
                        "Any phone camera can scan your QR code and offer Add contact, even without NambaLink.\n\n" +
                        "Safety: always check the name M-PESA shows before you confirm a payment."
                )
                .setPositiveButton("OK", null)
                .show()
        })

        u.addView(button("Privacy", slate) {
            AlertDialog.Builder(this)
                .setTitle("Privacy")
                .setMessage(
                    "Your profile and saved numbers stay on this phone. NambaLink does not upload them. " +
                        "Only share a QR code that you want others to have, because anyone who scans it gets the details inside."
                )
                .setPositiveButton("OK", null)
                .show()
        })
    }

    // ---- QR code, poster, PDF ----

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = 1
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M

        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun fit(paint: Paint, text: String, maxWidth: Float, startSize: Float) {
        paint.textSize = startSize

        while (paint.measureText(text) > maxWidth && paint.textSize > 20f) {
            paint.textSize = paint.textSize - 2f
        }
    }

    // An A4 poster at 150 dpi
    private fun makePoster(p: Person): Bitmap {
        val w = 1240
        val h = 1754
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textAlign = Paint.Align.CENTER

        // Header
        paint.color = teal
        c.drawRect(0f, 0f, w.toFloat(), 340f, paint)

        paint.color = Color.WHITE
        paint.typeface = Typeface.DEFAULT_BOLD
        fit(paint, p.title, 1100f, 120f)
        c.drawText(p.title, w / 2f, 190f, paint)

        paint.typeface = Typeface.DEFAULT
        paint.textSize = 46f
        c.drawText("Scan to save my number", w / 2f, 280f, paint)

        // QR code
        val qr = qrBitmap(Namba.vCard(p), 860)
        c.drawBitmap(qr, (w - qr.width) / 2f, 410f, null)

        // Phone number
        paint.color = ink
        paint.typeface = Typeface.DEFAULT_BOLD
        val number = Namba.pretty(p.phone)
        fit(paint, number, 1100f, 110f)
        c.drawText(number, w / 2f, 1410f, paint)

        // Payment details
        var y = 1500f
        paint.typeface = Typeface.DEFAULT_BOLD

        if (p.till.isNotEmpty()) {
            val line = "Till number: " + p.till
            fit(paint, line, 1100f, 70f)
            c.drawText(line, w / 2f, y, paint)
            y += 80f
        }

        if (p.paybill.isNotEmpty()) {
            val line = "Paybill: " + p.paybill
            fit(paint, line, 1100f, 70f)
            c.drawText(line, w / 2f, y, paint)
            y += 80f

            if (p.acc.isNotEmpty()) {
                val acc = "Account: " + p.acc
                fit(paint, acc, 1100f, 60f)
                c.drawText(acc, w / 2f, y, paint)
                y += 70f
            }
        }

        // Footer
        paint.typeface = Typeface.DEFAULT
        paint.color = grey
        paint.textSize = 38f
        c.drawText("Use your phone camera to scan", w / 2f, 1650f, paint)
        paint.textSize = 32f
        c.drawText("Made with NambaLink", w / 2f, 1710f, paint)

        return bmp
    }

    private fun fileName(p: Person, ext: String, kind: String): String {
        val safe = p.title.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(30)
        return "NambaLink-" + kind + "-" + (if (safe.isEmpty()) "me" else safe) + "." + ext
    }

    private fun savePoster(p: Person, pdf: Boolean) {
        try {
            val poster = makePoster(p)

            if (pdf) {
                val doc = PdfDocument()
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
                page.canvas.drawBitmap(poster, null, Rect(0, 0, 595, 842), Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)

                val out = ByteArrayOutputStream()
                doc.writeTo(out)
                doc.close()

                saveFile(codePdf, fileName(p, "pdf", "poster"), "application/pdf", out.toByteArray())
            } else {
                val out = ByteArrayOutputStream()
                poster.compress(Bitmap.CompressFormat.PNG, 100, out)

                saveFile(codePng, fileName(p, "png", "poster"), "image/png", out.toByteArray())
            }
        } catch (e: Exception) {
            toast("Could not make the poster")
        }
    }

    private fun saveQrOnly(p: Person) {
        try {
            val out = ByteArrayOutputStream()
            qrBitmap(Namba.vCard(p), 1000).compress(Bitmap.CompressFormat.PNG, 100, out)

            saveFile(codePng, fileName(p, "png", "qr"), "image/png", out.toByteArray())
        } catch (e: Exception) {
            toast("Could not make the QR code")
        }
    }

    private fun saveFile(code: Int, name: String, mime: String, bytes: ByteArray) {
        pendingBytes = bytes

        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.putExtra(Intent.EXTRA_TITLE, name)

        try {
            startActivityForResult(i, code)
        } catch (e: Exception) {
            toast("No file manager found")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val uri = data?.data

        if (resultCode != RESULT_OK || uri == null) return

        if (requestCode == codePng || requestCode == codePdf) {
            val bytes = pendingBytes ?: return
            pendingBytes = null

            thread {
                val ok = try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    true
                } catch (e: Exception) {
                    false
                }

                runOnUiThread {
                    toast(if (ok) "Saved. Print it and put it where customers can see it." else "Could not save the file")
                }
            }
        }
    }

    // ---- Updates ----

    private fun checkUpdate() {
        updateText = "Checking…"
        render()

        thread {
            try {
                val latest = UpdateChecker.fetchLatest()
                val mine = UpdateChecker.installedVersion(this)

                runOnUiThread {
                    if (latest != null && UpdateChecker.isNewer(latest.version, mine)) {
                        updateText = "Update ${latest.version} available"
                        render()

                        AlertDialog.Builder(this)
                            .setTitle("Update ${latest.version}")
                            .setMessage(if (latest.notes.isEmpty()) "A new version is ready." else latest.notes)
                            .setPositiveButton("Download") { _, _ -> startDownload(latest.docId) }
                            .setNegativeButton("Later", null)
                            .show()
                    } else {
                        updateText = "Up to date ($mine)"
                        render()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateText = "Check for updates"
                    render()
                    toast("Could not check. Try again with data on.")
                }
            }
        }
    }

    private fun startDownload(docId: String) {
        toast("Downloading…")

        thread {
            try {
                val url = UpdateChecker.requestDownloadUrl(docId)

                runOnUiThread {
                    val file = File(getExternalFilesDir(null), "NambaLink-update.apk")
                    if (file.exists()) file.delete()

                    val req = DownloadManager.Request(Uri.parse(url))
                        .setTitle("NambaLink update")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(this, null, "NambaLink-update.apk")

                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadId = dm.enqueue(req)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Download failed. Try again.") }
            }
        }
    }

    private fun installDownloaded() {
        val file = File(getExternalFilesDir(null), "NambaLink-update.apk")
        if (!file.exists()) return

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("Allow NambaLink to install apps, then download again")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        // targetSdk 28 lets us use a plain file URI
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(i)
    }

    // ---- Forms ----

    /**
     * A dialog with text boxes. onOk gets the typed values. It returns an
     * error message to keep the dialog open, or null when it is done.
     */
    private fun form(
        title: String,
        fields: List<Field>,
        okLabel: String,
        onOk: (List<String>) -> String?
    ) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val inputs = ArrayList<EditText>()

        for (f in fields) {
            val e = EditText(this)
            e.hint = f.hint
            e.setText(f.initial)

            if (f.kind == Field.MULTI) {
                e.minLines = 3
                e.gravity = Gravity.TOP
            } else {
                e.setSingleLine()
            }

            e.inputType = when (f.kind) {
                Field.PHONE -> InputType.TYPE_CLASS_PHONE
                Field.NUMBER -> InputType.TYPE_CLASS_NUMBER
                Field.PLAIN -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                Field.MULTI -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }

            box.addView(e)
            inputs.add(e)
        }

        val error = TextView(this)
        error.setTextColor(red)
        error.textSize = 13f
        box.addView(error)

        val scroll = ScrollView(this)
        scroll.addView(box)

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(okLabel, null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val problem = onOk(inputs.map { it.text.toString() })

                if (problem == null) {
                    dialog.dismiss()
                } else {
                    error.text = problem
                }
            }
        }

        dialog.show()
    }

    // ---- Small helpers ----

    private fun footer() {
        val f = TextView(this)
        f.text = "NambaLink build ${UpdateChecker.installedVersion(this)}\nPowered by dehemanuelssolutions.co.ke"
        f.textSize = 12f
        f.gravity = Gravity.CENTER
        f.setTextColor(grey)
        f.setPadding(0, dp(20), 0, 0)
        f.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dehemanuelssolutions.co.ke/")))
        }
        root.addView(f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))

        val shape = GradientDrawable()
        shape.setColor(Color.WHITE)
        shape.cornerRadius = dp(14).toFloat()
        c.background = shape

        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        root.addView(c, lp)

        return c
    }

    private fun heading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 18f
        t.setTypeface(null, Typeface.BOLD)
        t.setTextColor(ink)
        t.setPadding(0, 0, 0, dp(6))
        return t
    }

    private fun body(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTextColor(grey)
        t.setPadding(0, dp(2), 0, dp(6))
        return t
    }

    private fun note(text: String) {
        val c = card()
        c.addView(body(text))
    }

    private fun button(text: String, color: Int, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.textSize = 15f

        val shape = GradientDrawable()
        shape.setColor(color)
        shape.cornerRadius = dp(10).toFloat()
        b.background = shape

        val lp = LinearLayout.LayoutParams(-1, dp(48))
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { click() }

        return b
    }
}
