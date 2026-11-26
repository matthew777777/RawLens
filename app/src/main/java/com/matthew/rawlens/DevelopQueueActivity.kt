// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

import android.app.Activity
import android.content.*
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Persistent DNG queue using the same cards, navigation rows and Material settings as the camera. */
class DevelopQueueActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(StableUiScale.wrap(base))
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var run: Button
    private lateinit var importer: Button
    private lateinit var heading: TextView
    private lateinit var mainPage: LinearLayout
    private lateinit var settingsPage: SettingsCardColumn
    private lateinit var settingsLink: LinearLayout
    private lateinit var exposure: SettingsSlider
    private lateinit var kit: SettingsKit
    private var onSettings=false
    // Sub-page ("Advanced tone & color" etc.) opened from Export settings.
    private lateinit var subHost: LinearLayout
    private lateinit var scroller: ScrollView
    private var subTitle: String?=null
    private var last: List<DevelopQueueItem>?=null
    private val previews=android.util.LruCache<String,Bitmap>(200)
    private val previewExecutor=Executors.newSingleThreadExecutor()
    private val previewCancel=CancellationSignal()
    private val failedPreviews=mutableSetOf<String>()
    private val previewWaiters=mutableMapOf<String,MutableList<Pair<WeakReference<ImageView>,WeakReference<TextView>>>>()
    private val refresh=object: Runnable { override fun run() { refreshQueue();handler.postDelayed(this,700) } }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(value: String,size: Float=13f,color: Int=R.color.text_secondary)=TextView(this).apply {
        this.text=value;textSize=size;setTextColor(getColor(color));includeFontPadding=false
    }
    private fun panel(radius: Int=20)=GradientDrawable().apply {
        cornerRadius=dp(radius).toFloat();setColor(getColor(R.color.surface_container))
    }
    private fun button(value: String,action: ()->Unit)=Button(this).apply {
        this.text=value;RawLensUi.styleButton(this);setOnClickListener { action() }
    }
    private fun icon(value: String,description: String,action: ()->Unit)=button(value,action).apply {
        contentDescription=description;textSize=25f;setPadding(0,0,0,0)
        val attr=android.util.TypedValue();theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,attr,true)
        setBackgroundResource(attr.resourceId)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        DevelopQueueLook.ensureSeeded(this)
        val root=column().apply { setPadding(dp(16),dp(8),dp(16),dp(12));setBackgroundColor(getColor(R.color.surface)) }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(icon("‹","Back") { goBack { finish() } },LinearLayout.LayoutParams(dp(48),dp(48)))
        heading=text("Develop queue",23f,R.color.text_primary).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);isAccessibilityHeading=true }
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(4) })
        importer=button("+ Import") { pickFiles() }.apply { setTextColor(getColor(R.color.accent)) }
        header.addView(importer,LinearLayout.LayoutParams(-2,dp(44)))
        header.addView(icon("⋮","Queue actions") { showQueueActions(header.getChildAt(header.childCount-1)) },LinearLayout.LayoutParams(dp(40),dp(48)))
        root.addView(header)
        val content=column()
        scroller=ScrollView(this).apply { isFillViewport=true;isVerticalScrollBarEnabled=false;addView(content) }
        root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        mainPage=column();content.addView(mainPage)
        settingsPage=SettingsCardColumn(this).apply { visibility=View.GONE };content.addView(settingsPage)
        subHost=column().apply { visibility=View.GONE };content.addView(subHost)
        kit=SettingsKit(this,settingsPage) { b,active -> RawLensUi.styleButton(b,active) }
        // Without these the collapsed "Advanced tone & color" link has nowhere to open.
        kit.openSubPageTitle={ subTitle }
        kit.openSubPage={ title,body -> openSubPage(title,body) }
        settingsLink=column().apply { background=panel() }
        mainPage.addView(settingsLink,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        val destination=text("JPEG  ·  DCIM/RawLens/Developed",12f).apply { setPadding(dp(4),dp(12),dp(4),dp(20)) }
        mainPage.addView(destination)
        val queueHeader=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),0,dp(4),dp(10)) }
        queueHeader.addView(text("PHOTOS",11f).apply { letterSpacing=.12f },LinearLayout.LayoutParams(0,-2,1f))
        summary=text("",12f);queueHeader.addView(summary)
        mainPage.addView(queueHeader)
        list=column();mainPage.addView(list)
        buildSettings()
        run=button("Export photos") { handlePrimaryAction() }
        root.addView(run,LinearLayout.LayoutParams(-1,dp(50)).apply { topMargin=dp(10) })
        setContentView(root);ImmersiveMode.apply(window);updateSettingsLink();refreshQueue()
    }
    /** Settings → Photo look, edited here and remembered for the queue only. */
    private val lookHost=object: JpegLookHost {
        override fun jpegOutputSettings() = DevelopQueueLook.settings(this@DevelopQueueActivity)
        override fun applyPhotoOutputSettings(settings: JpegOutputSettings) = true
        override fun lensPreferences() = DevelopQueueLook.prefs(this@DevelopQueueActivity)
        override fun setStatus(message: String) = toast(message)
        override fun showPhotoLutSettings() = Unit
        override fun reshow() { saveExposure();buildSettings();updateSettingsLink() }
    }
    private fun openSubPage(title: String,body: View) {
        val refresh=subTitle==title
        (body.parent as? ViewGroup)?.removeView(body)
        subHost.removeAllViews();subHost.addView(body,LinearLayout.LayoutParams(-1,-2))
        subTitle=title;settingsPage.visibility=View.GONE;subHost.visibility=View.VISIBLE
        heading.text=title
        if(!refresh) scroller.scrollTo(0,0)
    }
    private fun closeSubPage() {
        if(subTitle==null) return
        subHost.removeAllViews();subHost.visibility=View.GONE;subTitle=null
        if(onSettings) { settingsPage.visibility=View.VISIBLE;heading.text="Export settings" }
    }
    private fun goBack(otherwise: ()->Unit) {
        when {
            subTitle!=null -> closeSubPage()
            onSettings -> showQueue()
            else -> otherwise()
        }
    }
    private fun buildSettings() {
        settingsPage.removeAllViews()
        kit.subPages.clear()
        settingsPage.addView(kit.sectionTitle("Camera look"))
        settingsPage.addView(kit.settingsButton("Copy from camera settings").apply {
            setOnClickListener {
                saveExposure();DevelopQueueLook.copyFromCamera(this@DevelopQueueActivity)
                buildSettings();updateSettingsLink();toast("Copied camera look")
            }
        })
        settingsPage.addView(kit.sectionDesc("Copies every tone, JPEG and colour setting from Photo settings. Changes made here are remembered for the queue and leave the camera unchanged."))
        settingsPage.addView(kit.sectionTitle("Exposure"))
        exposure=slider("Exposure compensation",80,((DevelopQueueLook.exposureEv(this)+4)*10).toInt()) { "${String.format(java.util.Locale.US,"%+.1f",it/10.0-4)} EV" }
        settingsPage.addView(kit.sectionDesc("Added to every queued photo on top of the look below."))
        JpegLookSettingsSection(kit,lookHost,cameraOnly=false).show()
        kit.polish()
    }
    private fun slider(name: String,max: Int,progress: Int,format: (Int)->String): SettingsSlider {
        val label=text("$name: ${format(progress)}")
        settingsPage.addView(label)
        return SettingsSlider(this).apply {
            this.max=max;this.progress=progress;contentDescription=name
            setOnSettingsSliderChangeListener(object: SettingsSlider.OnSettingsSliderChangeListener {
                override fun onProgressChanged(seekBar: SettingsSlider,progress: Int,fromUser: Boolean) { label.text="$name: ${format(progress)}" }
                override fun onStartTrackingTouch(seekBar: SettingsSlider) {}
                override fun onStopTrackingTouch(seekBar: SettingsSlider) {}
            })
            settingsPage.addView(this,LinearLayout.LayoutParams(-1,-2))
        }
    }
    private fun saveExposure() { if(::exposure.isInitialized) DevelopQueueLook.setExposureEv(this,exposure.progress/10.0-4) }
    private fun updateSettingsLink() {
        val look=DevelopQueueLook.settings(this)
        val summary="${look.toneMapper.label} · ${if(look.adaptiveExposureAuto) "Auto exposure" else "Manual exposure"} · Quality ${look.jpegQuality}"
        settingsLink.removeAllViews()
        settingsLink.addView(kit.navRow("Export settings",summary,R.drawable.ic_tune) {
            onSettings=true;mainPage.visibility=View.GONE;settingsPage.visibility=View.VISIBLE
            heading.text="Export settings";importer.visibility=View.GONE;refreshQueue()
        })
    }
    private fun showQueue() {
        closeSubPage();saveExposure();onSettings=false;mainPage.visibility=View.VISIBLE;settingsPage.visibility=View.GONE
        heading.text="Develop queue";importer.visibility=View.VISIBLE;updateSettingsLink();refreshQueue()
    }
    private fun handlePrimaryAction() {
        if(onSettings) { showQueue();return }
        if(DevelopQueueService.running) { DevelopQueueService.pause();refreshQueue();return }
        val items=DevelopQueue.snapshot(this)
        if(items.none { it.state=="Waiting" }) {
            val again=if(items.any { it.state=="Exported" }) "Exported" else "Failed"
            items.filter { it.state==again }.forEach { DevelopQueue.update(this,it.copy(state="Waiting",detail="")) }
        }
        saveExposure()
        runCatching { DevelopQueueService.start(this) }.onFailure { toast(it.message ?: "Could not start export") }
        refreshQueue()
    }
    private fun setEnabledTree(view: View,enabled: Boolean) {
        view.isEnabled=enabled
        (view as? ViewGroup)?.let { group -> for(i in 0 until group.childCount) setEnabledTree(group.getChildAt(i),enabled) }
    }
    private fun showQueueActions(anchor: View) {
        val menu=PopupMenu(this,anchor)
        menu.menu.add("Retry failed").setOnMenuItemClickListener {
            DevelopQueue.snapshot(this).filter { row -> row.state=="Failed" }.forEach { row -> DevelopQueue.update(this,row.copy(state="Waiting",detail="")) }
            refreshQueue();true
        }
        menu.menu.add("Clear completed").setOnMenuItemClickListener {
            val old=DevelopQueue.snapshot(this).map { row -> row.uri };DevelopQueue.clearCompleted(this);releaseUnused(old);refreshQueue();true
        }
        menu.menu.add("About DNG imports").setOnMenuItemClickListener {
            RawLensDialogBuilder(this).setTitle("DNG imports")
                .setMessage("Import Bayer or linear RGB DNGs, including compressed files. Each photo uses its own white balance and camera profile. Unsupported files are reported individually.\n\nExports save to DCIM/RawLens/Developed. The queue stays saved when you leave; processing continues in the background.\n\nLimits: 200 queued photos, 50 MP and 512 MB per DNG.")
                .setPositiveButton("Got it",null).show();true
        }
        menu.show()
    }
    private fun pickFiles() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type="*/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        },101)
    }
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data)
        if(request!=101 || result!=RESULT_OK || data==null) return
        val uris=data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        val sources=mutableListOf<Pair<String,String>>();var failures=0
        uris.distinct().take(200).forEach { uri -> runCatching {
            val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                if(it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: "Imported DNG"
            require(name.endsWith(".dng",true)) { "Choose DNG files" }
            contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            sources+=uri.toString() to name
        }.onFailure { failures++ } }
        val added=DevelopQueue.add(this,sources)
        toast("Added $added photo${if(added==1) "" else "s"}"+if(failures>0) " · $failures could not be imported" else "")
        refreshQueue()
    }
    private fun refreshQueue() {
        val items=DevelopQueue.snapshot(this);val busy=DevelopQueueService.running
        setEnabledTree(settingsPage,!busy);importer.isEnabled=!busy
        val waiting=items.count { it.state=="Waiting" };val done=items.count { it.state=="Exported" };val failed=items.count { it.state=="Failed" }
        summary.text=if(busy) "Exporting · $done / ${items.size}" else when {
            waiting>0 -> "$waiting waiting · $done saved"
            failed>0 -> "$done saved · $failed failed"
            else -> "${items.size} ${if(items.size==1) "photo" else "photos"}"
        }
        val runnable=waiting>0 || done>0 || failed>0
        run.text=when {
            onSettings -> "Done"
            busy -> if(DevelopQueueService.stopRequested) "Stopping after current…" else "Stop after current"
            waiting>0 -> "Export $waiting ${if(waiting==1) "photo" else "photos"}"
            done>0 -> "Export again · $done ${if(done==1) "photo" else "photos"}"
            failed>0 -> "Retry $failed ${if(failed==1) "photo" else "photos"}"
            else -> "Export photos"
        }
        run.isEnabled=onSettings || if(busy) !DevelopQueueService.stopRequested else runnable
        RawLensUi.styleButton(run,run.isEnabled && !busy)
        if(items==last) return
        last=items;list.removeAllViews()
        if(items.isEmpty()) list.addView(column().apply {
            gravity=Gravity.CENTER;setPadding(dp(24),dp(48),dp(24),dp(48))
            addView(text("No photos queued",18f,R.color.text_primary))
            addView(text("Tap Import to choose your DNGs.",13f).apply { setPadding(0,dp(10),0,0) })
        })
        items.forEachIndexed { index,item -> list.addView(queueRow(index,item),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }) }
    }
    private fun queueRow(index: Int,item: DevelopQueueItem): View {
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;background=panel(16);setPadding(dp(12),dp(12),dp(4),dp(12)) }
        val thumb=FrameLayout(this).apply { background=panel(12).apply { setColor(getColor(R.color.accent_soft)) };clipToOutline=true }
        val placeholder=text("DNG",11f,R.color.accent).apply { gravity=Gravity.CENTER;letterSpacing=.08f }
        thumb.addView(placeholder,FrameLayout.LayoutParams(-1,-1))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
        thumb.addView(image,FrameLayout.LayoutParams(-1,-1))
        row.addView(thumb,LinearLayout.LayoutParams(dp(58),dp(58)).apply { marginEnd=dp(12) })
        val copy=column()
        copy.addView(text(item.name,14f,R.color.text_primary).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END })
        val status=when(item.state) { "Exported" -> "Saved";"Developing" -> "Exporting";else -> item.state }
        copy.addView(text(status,12f,when(item.state) { "Failed" -> R.color.danger;"Developing","Exported" -> R.color.accent;else -> R.color.text_secondary }).apply { setPadding(0,dp(6),0,0) })
        if(item.state=="Failed" || item.state=="Developing") copy.addView(text(item.detail,11f).apply { maxLines=2;ellipsize=TextUtils.TruncateAt.END;setPadding(0,dp(4),0,0) })
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        val more=icon("⋮","Actions for ${item.name}") { }
        more.setOnClickListener { showItemActions(more,item) }
        row.addView(more,LinearLayout.LayoutParams(dp(40),dp(48)))
        if(item.output!=null) { row.isClickable=true;row.isFocusable=true;row.setOnClickListener { openOutput(item.output) } }
        row.contentDescription="Photo ${index+1}. ${item.name}. $status"
        loadPreview(item.output ?: item.uri,image,placeholder)
        return row
    }
    private fun showItemActions(anchor: View,item: DevelopQueueItem) {
        val menu=PopupMenu(this,anchor)
        fun action(name: String,block: ()->Unit) { menu.menu.add(name).setOnMenuItemClickListener { block();refreshQueue();true } }
        item.output?.let { action("View export") { openOutput(it) } }
        if(item.state in listOf("Exported","Failed")) action(if(item.state=="Failed") "Retry" else "Queue again") {
            DevelopQueue.update(this,item.copy(state="Waiting",detail=""))
        }
        if(item.state=="Waiting") {
            action("Move up") { DevelopQueue.move(this,item.id,-1) }
            action("Move down") { DevelopQueue.move(this,item.id,1) }
        }
        if(item.state!="Developing") action("Remove from queue") { DevelopQueue.remove(this,item.id);releaseUnused(listOf(item.uri)) }
        menu.show()
    }
    private fun openOutput(uri: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri),"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { toast("Could not open export") }
    }
    private fun loadPreview(uri: String,image: ImageView,label: TextView) {
        previews.get(uri)?.let { image.setImageBitmap(it);label.visibility=View.GONE;return }
        if(uri in failedPreviews) return
        val targets=previewWaiters[uri]
        val target=WeakReference(image) to WeakReference(label)
        if(targets!=null) { targets.add(target);return }
        previewWaiters[uri]=mutableListOf(target)
        val resolver=applicationContext.contentResolver
        previewExecutor.execute {
            val bitmap=runCatching { resolver.loadThumbnail(Uri.parse(uri),android.util.Size(144,144),previewCancel) }.getOrNull()
            handler.post {
                if(isDestroyed) return@post
                if(bitmap!=null) previews.put(uri,bitmap) else failedPreviews.add(uri)
                previewWaiters.remove(uri)?.forEach { (view,note) -> if(bitmap!=null) {
                    view.get()?.setImageBitmap(bitmap);note.get()?.visibility=View.GONE
                } }
            }
        }
    }
    private fun releaseUnused(uris: List<String>) {
        val remaining=DevelopQueue.snapshot(this).map { it.uri }.toSet()
        uris.distinct().filter { it !in remaining }.forEach { uri -> runCatching {
            contentResolver.releasePersistableUriPermission(Uri.parse(uri),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } }
    }
    private fun toast(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { goBack { finishAfterTransition() } }
    override fun onResume() { super.onResume();handler.post(refresh);ImmersiveMode.apply(window) }
    override fun onPause() { handler.removeCallbacks(refresh);saveExposure();super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null);previewCancel.cancel();previewExecutor.shutdownNow()
        previewWaiters.clear();previews.evictAll();super.onDestroy()
    }
}
