package app.pub.hardware

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.*
import java.util.UUID
import java.util.concurrent.Executors

/** USB bulk endpoints only. A native chooser and Android's device grant precede any I/O. */
class UsbHardware(private val context:Context,private val choose:(List<DeviceChoice>,(String?)->Unit)->Unit) {
    private val manager=context.getSystemService(UsbManager::class.java)
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var closed=false
    private var selected:UsbDevice?=null
    @Volatile private var connection:UsbDeviceConnection?=null
    private var claimed:UsbInterface?=null
    private var receiver:BroadcastReceiver?=null
    private var pendingGrant:PendingIntent?=null
    private var busy=false
    private var epoch=0L
    fun handle(operation:String,args:JsonObject,emit:(String,JsonObject)->Unit) {
        if(closed) return
        fun error(message:String)=emit("error",JsonObject().apply {addProperty("message",message)})
        fun result(data:JsonObject=JsonObject())=emit("result",data.apply {addProperty("operation",operation)})
        if(operation=="disconnect") { disconnect();result();return }
        if(operation=="choose") {
            if(busy) {error("A USB selection is already pending.");return}
            disconnect();busy=true
            val selectionEpoch=epoch
            val choices=manager.deviceList.values.take(32).associateBy { UUID.randomUUID().toString() }
            choose(choices.map { (id,d)->DeviceChoice(id,"${d.productName ?: "USB device"} (${d.vendorId}:${d.productId})") }) { id ->
                if(closed || epoch!=selectionEpoch) return@choose
                val device=choices[id]
                if(device==null) {busy=false;emit("cancelled",JsonObject());return@choose}
                val open={
                    if(!closed && epoch==selectionEpoch) {
                    busy=false
                    if(!closed && manager.hasPermission(device)) {
                        val conn=manager.openDevice(device)
                        if(conn==null) error("USB device could not be opened.") else {
                            selected=device;connection=conn
                            emit("device",JsonObject().apply {
                                addProperty("name",device.productName ?: "USB device")
                                addProperty("vendorId",device.vendorId);addProperty("productId",device.productId)
                                add("interfaces",JsonArray().apply {
                                    for(i in 0 until minOf(device.interfaceCount,16)) {
                                        val face=device.getInterface(i)
                                        add(JsonObject().apply {
                                            addProperty("index",i);addProperty("class",face.interfaceClass)
                                            add("endpoints",JsonArray().apply {
                                                for(e in 0 until minOf(face.endpointCount,16)) {
                                                    val ep=face.getEndpoint(e)
                                                    add(JsonObject().apply {addProperty("address",ep.address);addProperty("direction",if(ep.direction==UsbConstants.USB_DIR_IN) "in" else "out");addProperty("type",ep.type);addProperty("maxPacketSize",ep.maxPacketSize)})
                                                }
                                            })
                                        })
                                    }
                                })
                            })
                        }
                    } else error("USB permission was not granted.")
                }
                }
                if(manager.hasPermission(device)) open() else {
                    val action="${context.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
                    val listener=object:BroadcastReceiver() {
                        override fun onReceive(c:Context,intent:Intent) {
                            if(intent.action!=action || epoch!=selectionEpoch) return
                            clearGrant();open()
                        }
                    }
                    receiver=listener
                    if(Build.VERSION.SDK_INT>=33) context.registerReceiver(listener,IntentFilter(action),Context.RECEIVER_NOT_EXPORTED)
                    else context.registerReceiver(listener,IntentFilter(action))
                    val pi=PendingIntent.getBroadcast(context,0,Intent(action).setPackage(context.packageName),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                    pendingGrant=pi;manager.requestPermission(device,pi)
                    main.postDelayed({if(receiver===listener && epoch==selectionEpoch) {clearGrant();busy=false;error("USB permission request timed out.")}},30000)
                }
            }
            return
        }
        val device=selected;val conn=connection
        if(device==null || conn==null || !manager.hasPermission(device)) {error("Choose and allow a USB device first.");return}
        if(busy) {error("USB operation in progress.");return}
        if(operation=="claim") {
            val index=args["interface"]?.asInt ?: 0
            require(index in 0 until device.interfaceCount)
            claimed?.let { conn.releaseInterface(it) };claimed=null
            val face=device.getInterface(index)
            if(conn.claimInterface(face,false)) {claimed=face;result()} else error("USB interface is in use or unavailable.")
            return
        }
        require(operation in setOf("read","write"))
        val face=claimed ?: run {error("Claim a USB interface first.");return}
        val address=args["endpoint"].asInt
        val endpoint=(0 until face.endpointCount).map(face::getEndpoint).find {it.address==address && it.type==UsbConstants.USB_ENDPOINT_XFER_BULK}
            ?: run {error("A bulk endpoint from the selected interface is required.");return}
        require(endpoint.direction==if(operation=="read") UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT)
        val timeout=(args["timeoutMs"]?.asInt ?: 1000).also {require(it in 1..2000)}
        val bytes=if(operation=="write") args["data"].asJsonArray.also {require(it.size() in 1..4096)}.map {it.asInt.also {v->require(v in 0..255)}.toByte()}.toByteArray()
            else ByteArray((args["maxBytes"]?.asInt ?: 512).also {require(it in 1..4096)})
        if(busy) {error("USB operation in progress.");return};busy=true
        val transferEpoch=epoch
        worker.execute {
            val count=runCatching {conn.bulkTransfer(endpoint,bytes,bytes.size,timeout)}.getOrDefault(-1)
            main.post {
                if(!closed && connection===conn && epoch==transferEpoch) {
                    busy=false
                    if(count<0) error("USB transfer timed out or device disconnected.")
                    else result(JsonObject().apply {addProperty("count",count);if(operation=="read") add("bytes",JsonArray().apply {bytes.take(count).forEach {add(it.toInt() and 255)}})})
                }
            }
        }
    }
    private fun clearGrant() {receiver?.let {runCatching {context.unregisterReceiver(it)}};receiver=null;pendingGrant?.cancel();pendingGrant=null}
    private fun disconnect() {epoch++;clearGrant();connection?.let {c->claimed?.let {runCatching {c.releaseInterface(it)}};runCatching {c.close()}};claimed=null;connection=null;selected=null;busy=false}
    fun close() {closed=true;main.removeCallbacksAndMessages(null);disconnect();worker.shutdownNow()}
}
