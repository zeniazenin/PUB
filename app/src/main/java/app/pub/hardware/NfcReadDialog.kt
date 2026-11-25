package app.pub.hardware

import app.pub.ui.PubTextButton

import android.app.Activity
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.*

@Composable
fun NfcReadDialog(onResult:(JsonObject)->Unit,onDismiss:()->Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val result by rememberUpdatedState(onResult)
    var status by remember {mutableStateOf("Hold an NFC tag near the back of your phone.")}
    DisposableEffect(context,lifecycle) {
        var c=context
        while(c is ContextWrapper && c !is Activity) c=c.baseContext
        val activity=c as? Activity
        val adapter=NfcAdapter.getDefaultAdapter(context)
        val main=Handler(Looper.getMainLooper())
        var live=true
        var delivered=false
        fun stop() {if(activity!=null) runCatching {adapter?.disableReaderMode(activity)}}
        fun start() {
            if(activity==null || adapter==null || !adapter.isEnabled) {status="NFC is unavailable or switched off in Android settings.";return}
            runCatching {adapter.enableReaderMode(activity,{tag->
                val data=JsonObject().apply {
                    add("technologies",JsonArray().apply {tag.techList.take(16).forEach {add(it.substringAfterLast('.'))}})
                    val records=Ndef.get(tag)?.cachedNdefMessage?.records ?: emptyArray()
                    var remaining=4096
                    add("records",JsonArray().apply {records.take(16).forEach {r->
                        if(r.payload.size<=remaining) {remaining-=r.payload.size;add(JsonObject().apply {
                            addProperty("tnf",r.tnf);addProperty("type",String(r.type,Charsets.US_ASCII).take(80))
                            addProperty("payloadBase64",Base64.encodeToString(r.payload,Base64.NO_WRAP))
                        })}
                    }})
                    addProperty("truncated",records.sumOf {it.payload.size}>4096 || records.size>16)
                }
                main.post {if(live && !delivered && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {delivered=true;stop();result(data)}}
            },NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,null)}
                .onFailure {status="Could not start NFC reading."}
        }
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME) start() else if(event==Lifecycle.Event.ON_PAUSE) stop()}
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        main.postDelayed({if(live && !delivered) {stop();status="No tag read. Close and try again."}},30000)
        onDispose {live=false;stop();main.removeCallbacksAndMessages(null);lifecycle.removeObserver(observer)}
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Read NFC tag")},text={Text(status)},confirmButton={PubTextButton(onClick=onDismiss){Text("Close")}})
}
